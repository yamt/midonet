/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.simulation

import akka.actor.ActorSystem
import akka.dispatch.{ExecutionContext, Future, Promise}
import java.util.UUID
import org.slf4j.LoggerFactory

import com.midokura.midolman.SimulationController
import com.midokura.midolman.SimulationController.EmitGeneratedPacket
import com.midokura.midolman.layer3.Route
import com.midokura.midolman.rules.RuleResult.{Action => RuleAction}
import com.midokura.midolman.simulation.Coordinator._
import com.midokura.midolman.topology.{RouterConfig, RoutingTableWrapper,
                                       VirtualTopologyActor}
import com.midokura.midolman.topology.VirtualTopologyActor.PortRequest
import com.midokura.midonet.cluster.client._
import com.midokura.packets.{ARP, Ethernet, ICMP, IntIPv4, IPv4, MAC}
import com.midokura.packets.ICMP.{EXCEEDED_CODE, UNREACH_CODE}
import com.midokura.sdn.flows.{WildcardMatch, WildcardMatches}


class Router(val id: UUID, val cfg: RouterConfig,
             val rTable: RoutingTableWrapper, val arpTable: ArpTable,
             val inFilter: Chain, val outFilter: Chain) extends Device {
    private val log = LoggerFactory.getLogger(classOf[Router])
    private val loadBalancer = new LoadBalancer(rTable)

    override def process(pktContext: PacketContext)
                        (implicit ec: ExecutionContext,
                         actorSystem: ActorSystem): Future[Action] = {
        if (pktContext.getMatch.getEtherType != IPv4.ETHERTYPE &&
                pktContext.getMatch.getEtherType != ARP.ETHERTYPE)
            return Promise.successful(new NotIPv4Action)(ec)

        getRouterPort(pktContext.getInPortId, pktContext.getExpiry) flatMap {
            case null => Promise.successful(new DropAction)(ec)
            case inPort => preRouting(pktContext, inPort)
        }
    }

    /* Does pre-routing and routing phases. Delegates post-routing and out
     * phases to postRouting()
     */
    private def preRouting(pktContext: PacketContext, inPort: RouterPort[_])
                          (implicit ec: ExecutionContext,
                           actorSystem: ActorSystem): Future[Action] = {
        val hwDst = pktContext.getMatch.getEthernetDestination
        if (Ethernet.isBroadcast(hwDst)) {
            // Broadcast packet:  Handle if ARP, drop otherwise.
            if (pktContext.getMatch.getEtherType == ARP.ETHERTYPE &&
                    pktContext.getMatch.getNetworkProtocol == ARP.OP_REQUEST) {
                log.debug("Processing ARP request")
                val arpPayload = pktContext.getFrame.getPayload
                if (arpPayload.isInstanceOf[ARP]) {
                    processArpRequest(arpPayload.asInstanceOf[ARP], inPort)
                } else {
                    log.warn("Non-ARP packet with ethertype ARP: {}",
                             arpPayload)
                }
                return Promise.successful(new ConsumedAction)(ec)
            } else
                return Promise.successful(new DropAction)(ec)
        }

        if (hwDst != inPort.portMac) {
            // Not addressed to us, log.warn and drop.
            log.warn("{} neither broadcast nor inPort's MAC ({})", hwDst,
                     inPort.portMac)
            return Promise.successful(new DropAction)(ec)
        }

        if (pktContext.getMatch.getEtherType == ARP.ETHERTYPE) {
            // Non-broadcast ARP.  Handle reply, drop rest.
            if (pktContext.getMatch.getNetworkProtocol == ARP.OP_REPLY) {
                log.info("Processing ARP reply")
                val arpPayload = pktContext.getFrame.getPayload
                if (arpPayload.isInstanceOf[ARP]) {
                    processArpReply(arpPayload.asInstanceOf[ARP],
                                    pktContext.getInPortId, inPort)
                } else {
                    log.warn("Non-ARP packet with ethertype ARP: {}",
                             arpPayload)
                }
                return Promise.successful(new ConsumedAction)(ec)
            } else
                return Promise.successful(new DropAction)(ec)
        }

        val nwDst = pktContext.getMatch.getNetworkDestinationIPv4
        if (nwDst.getAddress == inPort.portAddr.getAddress) {
            // We're the L3 destination.  Reply to ICMP echos, drop the rest.
            if (isIcmpEchoRequest(pktContext.getMatch)) {
                log.debug("got ICMP echo")
                sendIcmpEchoReply(pktContext.getMatch, pktContext.getFrame,
                                  pktContext.getExpiry)
                return Promise.successful(new ConsumedAction)(ec)
            } else
                return Promise.successful(new DropAction)(ec)
        }

        // Apply the pre-routing (ingress) chain
        // InputPort already set.
        pktContext.setOutputPort(null)
        val preRoutingResult = Chain.apply(inFilter, pktContext,
                                           pktContext.getMatch, id, false)
        if (preRoutingResult.action == RuleAction.DROP)
            return Promise.successful(new DropAction)(ec)
        else if (preRoutingResult.action == RuleAction.REJECT) {
            sendIcmpError(inPort, pktContext.getMatch, pktContext.getFrame,
                ICMP.TYPE_UNREACH, UNREACH_CODE.UNREACH_FILTER_PROHIB)
            return Promise.successful(new DropAction)(ec)
        } else if (preRoutingResult.action != RuleAction.ACCEPT) {
            log.error("Pre-routing for {} returned an action which was {}, " +
                      "not ACCEPT, DROP, or REJECT.", id,
                      preRoutingResult.action)
            return Promise.successful(new ErrorDropAction)(ec)
        }
        if (preRoutingResult.pmatch ne pktContext.getMatch) {
            log.error("Pre-routing for {} returned a different match object.",
                      id)
            return Promise.successful(new ErrorDropAction)(ec)
        }

        /* TODO(D-release): Have WildcardMatch take a DecTTLBy instead,
         * so that there need only be one sim. run for different TTLs.  */
        if (pktContext.getMatch.getNetworkTTL != null) {
            val ttl: Byte = pktContext.getMatch.getNetworkTTL
            if (ttl <= 1) {
                sendIcmpError(inPort, pktContext.getMatch, pktContext.getFrame,
                    ICMP.TYPE_TIME_EXCEEDED, EXCEEDED_CODE.EXCEEDED_TTL)
                return Promise.successful(new DropAction)(ec)
            } else {
                pktContext.getMatch.setNetworkTTL((ttl - 1).toByte)
            }
        }

        val rt: Route = loadBalancer.lookup(pktContext.getMatch)
        if (rt == null) {
            // No route to network
            log.debug("Route lookup: No route to network (dst:{}), {}",
                pktContext.getMatch.getNetworkDestinationIPv4, rTable.rTable)
            sendIcmpError(inPort, pktContext.getMatch, pktContext.getFrame,
                ICMP.TYPE_UNREACH, UNREACH_CODE.UNREACH_NET)
            return Promise.successful(new DropAction)(ec)
        }
        if (rt.nextHop == Route.NextHop.BLACKHOLE) {
            log.debug("Dropping packet, BLACKHOLE route (dst:{})",
                pktContext.getMatch.getNetworkDestinationIPv4)
            return Promise.successful(new DropAction)(ec)
        }
        if (rt.nextHop == Route.NextHop.REJECT) {
            sendIcmpError(inPort, pktContext.getMatch, pktContext.getFrame,
                ICMP.TYPE_UNREACH, UNREACH_CODE.UNREACH_FILTER_PROHIB)
            log.debug("Dropping packet, REJECT route (dst:{})",
                pktContext.getMatch.getNetworkDestinationIPv4)
            return Promise.successful(new DropAction)(ec)
        }
        if (rt.nextHop != Route.NextHop.PORT) {
            log.error("Routing table lookup for {} returned invalid nextHop " +
                "of {}", nwDst, rt.nextHop)
            // TODO(jlm, pino): Should this be an exception?
            return Promise.successful(new DropAction)(ec)
        }
        if (rt.nextHopPort == null) {
            log.error("Routing table lookup for {} forwarded to port null.",
                nwDst)
            // TODO(pino): should we remove this route?
            return Promise.successful(new DropAction)(ec)
        }

        getRouterPort(rt.nextHopPort, pktContext.getExpiry) flatMap {
            case null => Promise.successful(new DropAction)(ec)
            case outPort => postRouting(inPort, outPort, rt, pktContext)
        }
    }

    private def postRouting(inPort: RouterPort[_], outPort: RouterPort[_],
                            rt: Route, pktContext: PacketContext)
                           (implicit ec: ExecutionContext,
                            actorSystem: ActorSystem): Future[Action] = {
        // Apply post-routing (egress) chain.
        pktContext.setOutputPort(outPort.id)
        val postRoutingResult = Chain.apply(outFilter, pktContext,
                                            pktContext.getMatch, id, false)
        if (postRoutingResult.action == RuleAction.DROP) {
            log.debug("PostRouting DROP rule")
            return Promise.successful(new DropAction)
        } else if (postRoutingResult.action == RuleAction.REJECT) {
            log.debug("PostRouting REJECT rule")
            sendIcmpError(inPort, pktContext.getMatch, pktContext.getFrame,
                ICMP.TYPE_UNREACH, UNREACH_CODE.UNREACH_FILTER_PROHIB)
            return Promise.successful(new DropAction)
        } else if (postRoutingResult.action != RuleAction.ACCEPT) {
            log.error("Post-routing for {} returned an action which was {}, " +
                      "not ACCEPT, DROP, or REJECT.", id,
                      postRoutingResult.action)
            return Promise.successful(new ErrorDropAction)
        }
        if (postRoutingResult.pmatch ne pktContext.getMatch) {
            log.error("Post-routing for {} returned a different match object.",
                      id)
            return Promise.successful(new ErrorDropAction)(ec)
        }

        if (pktContext.getMatch.getNetworkDestinationIPv4.getAddress ==
                                         outPort.portAddr.getAddress) {
            if (isIcmpEchoRequest(pktContext.getMatch)) {
                log.debug("got icmp echo request")
                sendIcmpEchoReply(pktContext.getMatch, pktContext.getFrame,
                                  pktContext.getExpiry)
                return Promise.successful(new ConsumedAction)(ec)
            } else {
                log.debug("dropping IPv4 packet addressed to me")
                return Promise.successful(new DropAction)(ec)
            }
        }

        // Set HWSrc
        pktContext.getMatch.setEthernetSource(outPort.portMac)
        // Set HWDst
        val macFuture = getNextHopMac(outPort, rt,
                                      pktContext.getMatch.getNetworkDestination,
                                      pktContext.getExpiry)
        macFuture map {
            case null =>
                if (rt.nextHopGateway == 0 || rt.nextHopGateway == -1) {
                    log.debug("icmp host unreachable, host mac unknown")
                    sendIcmpError(inPort, pktContext.getMatch,
                                  pktContext.getFrame, ICMP.TYPE_UNREACH,
                                  UNREACH_CODE.UNREACH_HOST)
                } else {
                    log.debug("icmp net unreachable, gw mac unknown")
                    sendIcmpError(inPort, pktContext.getMatch,
                                  pktContext.getFrame, ICMP.TYPE_UNREACH,
                                  UNREACH_CODE.UNREACH_NET)
                }
                new DropAction: Action
            case nextHopMac =>
                log.debug("routing packet to {}", nextHopMac)
                pktContext.getMatch.setEthernetDestination(nextHopMac)
                new ToPortAction(rt.nextHopPort): Action
        }
    }

    private def getRouterPort(portID: UUID, expiry: Long)
            (implicit actorSystem: ActorSystem): Future[RouterPort[_]] = {
        val virtualTopologyManager = VirtualTopologyActor.getRef(actorSystem)
        expiringAsk(virtualTopologyManager, PortRequest(portID, false),
                    expiry).mapTo[RouterPort[_]] map {
            case null =>
                log.error("Can't find router port: {}", portID)
                null
            case rtrPort => rtrPort
        }
    }

    private def processArpRequest(pkt: ARP, inPort: RouterPort[_])
                                 (implicit ec: ExecutionContext,
                                  actorSystem: ActorSystem) {
        if (pkt.getProtocolType != ARP.PROTO_TYPE_IP)
            return
        val tpa = IPv4.toIPv4Address(pkt.getTargetProtocolAddress)
        if (tpa != inPort.portAddr.addressAsInt)
            return

        val spa = IPv4.toIPv4Address(pkt.getSenderProtocolAddress)
        val spaNet = new IntIPv4(spa, inPort.portAddr.getMaskLength)
        if (inPort.portAddr.toNetworkAddress != spaNet.toNetworkAddress) {
            log.debug("Ignoring ARP request from address {} not in the " +
                "ingress port network {}",
                spaNet, inPort.portAddr.toNetworkAddress)
            return
        }

        arpTable.set(new IntIPv4(spa), pkt.getSenderHardwareAddress)

        val arp = new ARP()
        arp.setHardwareType(ARP.HW_TYPE_ETHERNET)
        arp.setProtocolType(ARP.PROTO_TYPE_IP)
        arp.setHardwareAddressLength(6)
        arp.setProtocolAddressLength(4)
        arp.setOpCode(ARP.OP_REPLY)
        arp.setSenderHardwareAddress(inPort.portMac)
        arp.setSenderProtocolAddress(pkt.getTargetProtocolAddress)
        arp.setTargetHardwareAddress(pkt.getSenderHardwareAddress)
        arp.setTargetProtocolAddress(pkt.getSenderProtocolAddress)

        log.debug("replying to ARP request from {} for {} with own mac {}",
            Array[Object] (IPv4.fromIPv4Address(spa),
                IPv4.fromIPv4Address(tpa), inPort.portMac))

        val eth = new Ethernet()
        eth.setPayload(arp)
        eth.setSourceMACAddress(inPort.portMac)
        eth.setDestinationMACAddress(pkt.getSenderHardwareAddress)
        eth.setEtherType(ARP.ETHERTYPE)
        SimulationController.getRef(actorSystem) ! EmitGeneratedPacket(
            inPort.id, eth)
    }

    private def processArpReply(pkt: ARP, portID: UUID, rtrPort: RouterPort[_])
                               (implicit actorSystem: ActorSystem) {
        // Verify the reply:  It's addressed to our MAC & IP, and is about
        // the MAC for an IPv4 address.
        if (pkt.getHardwareType != ARP.HW_TYPE_ETHERNET ||
                pkt.getProtocolType != ARP.PROTO_TYPE_IP) {
            log.debug("Router {} ignoring ARP reply on port {} because hwtype "+
                      "wasn't Ethernet or prototype wasn't IPv4.", id, portID)
            return
        }
        val tpa: Int = IPv4.toIPv4Address(pkt.getTargetProtocolAddress)
        val tha: MAC = pkt.getTargetHardwareAddress
        if (tpa != rtrPort.portAddr.addressAsInt || tha != rtrPort.portMac) {
            log.debug("Router {} ignoring ARP reply on port {} because tpa or "+
                      "tha doesn't match.", id, portID)
            return
        }
        // Question:  Should we check if the ARP reply disagrees with an
        // existing cache entry and make noise if so?

        val sha: MAC = pkt.getSenderHardwareAddress
        val spa = IPv4.toIPv4Address(pkt.getSenderProtocolAddress)
        val spaNet = new IntIPv4(spa, rtrPort.portAddr.getMaskLength)
        if (rtrPort.portAddr.toNetworkAddress != spaNet.toNetworkAddress) {
            log.debug("Ignoring ARP reply from address {} not in the ingress " +
                "port network {}", spaNet, rtrPort.portAddr.toNetworkAddress)
            return
        }

        arpTable.set(new IntIPv4(spa), sha)
    }

    private def isIcmpEchoRequest(mmatch: WildcardMatch): Boolean = {
        mmatch.getNetworkProtocol == ICMP.PROTOCOL_NUMBER &&
            (mmatch.getTransportSource & 0xff) == ICMP.TYPE_ECHO_REQUEST &&
            (mmatch.getTransportDestination & 0xff) == ICMP.CODE_NONE
    }

    private def sendIcmpEchoReply(ingressMatch: WildcardMatch, packet: Ethernet,
                                  expiry: Long)
                    (implicit ec: ExecutionContext, actorSystem: ActorSystem) {
        val echo = packet.getPayload match {
            case ip: IPv4 =>
                ip.getPayload match {
                    case icmp: ICMP => icmp
                    case _ => null
                }
            case _ => null
        }
        if (echo == null)
            return

        val reply = new ICMP()
        reply.setEchoReply(echo.getIdentifier, echo.getSequenceNum, echo.getData)
        val ip = new IPv4()
        ip.setProtocol(ICMP.PROTOCOL_NUMBER)
        ip.setDestinationAddress(ingressMatch.getNetworkSource)
        ip.setSourceAddress(ingressMatch.getNetworkDestination)
        ip.setPayload(reply)

        sendIPPacket(ip, expiry)
    }

    private def getPeerMac(rtrPort: InteriorRouterPort, expiry: Long)
                          (implicit ec: ExecutionContext,
                           actorSystem: ActorSystem): Future[MAC] = {
        val virtualTopologyManager = VirtualTopologyActor.getRef(actorSystem)
        val peerPortFuture = expiringAsk(virtualTopologyManager,
                PortRequest(rtrPort.peerID, false), expiry).mapTo[Port[_]]
        peerPortFuture map {
            case null =>
                log.error("getPeerMac: cannot get port {}", rtrPort.peerID)
                null
            case rp: RouterPort[_] =>
                rp.portMac
            case nrp =>
                log.error("getPeerMac asked for MAC of non-router port {}", nrp)
                null
        }
    }

    private def getMacForIP(port: RouterPort[_], nextHopIP: Int, expiry: Long)
                           (implicit ec: ExecutionContext,
                            actorSystem: ActorSystem): Future[MAC] = {
        val nwAddr = new IntIPv4(nextHopIP)
        port match {
            case extPort: ExteriorRouterPort =>
                val shift = 32 - extPort.nwLength
                // Shifts by 32 in java are no-ops (see
                // http://www.janeg.ca/scjp/oper/shift.html), so special case
                // nwLength=0 <=> shift=32 to always match.
                if ((nextHopIP >>> shift) !=
                        (extPort.nwAddr.addressAsInt >>> shift) &&
                        shift != 32) {
                    log.warn("getMacForIP: cannot get MAC for {} - address " +
                        "not in network segment of port {} ({}/{})",
                        Array[Object](nwAddr, port.id,
                            extPort.nwAddr.toString,
                            extPort.nwLength.toString))
                    return Promise.successful(null)(ec)
                }
            case _ => /* Fall through */
        }
        arpTable.get(nwAddr, port, expiry)
    }

    /**
     * Given a route and a destination address, return the MAC address of
     * the next hop (or the destination's if it's a link-local destination)
     *
     * @param rt Route that the packet will be sent through
     * @param ipv4Dest Final destination of the packet to be sent
     * @param expiry
     * @param ec
     * @return
     */
    private def getNextHopMac(outPort: RouterPort[_], rt: Route,
                              ipv4Dest: Int, expiry: Long)
                             (implicit ec: ExecutionContext,
                              actorSystem: ActorSystem): Future[MAC] = {
        if (outPort == null)
            return Promise.successful(null)(ec)
        var peerMacFuture: Future[MAC] = null
        outPort match {
            case interior: InteriorRouterPort =>
                if (interior.peerID == null) {
                    log.warn("Packet sent to dangling logical port {}",
                        rt.nextHopPort)
                    return Promise.successful(null)(ec)
                }
                peerMacFuture = getPeerMac(interior, expiry)
            case _ => /* Fall through to ARP'ing below. */
                peerMacFuture = Promise.successful(null)(ec)
        }

        var nextHopIP: Int = rt.nextHopGateway
        if (nextHopIP == 0 || nextHopIP == -1) {  /* Last hop */
            nextHopIP = ipv4Dest
        }
        peerMacFuture flatMap {
            case null => getMacForIP(outPort, nextHopIP, expiry)(ec, actorSystem)
            case mac => Promise.successful(mac)
        }
    }

    /**
     * Send a locally generated IP packet
     *
     * CAVEAT: this method may block, so it is suitable only for use in
     * the context of processing packets that result in a CONSUMED action.
     *
     * XXX (pino, guillermo): should we add the ability to queue simulation of
     * this device starting at a specific step? In this case it would be the
     * routing step.
     *
     * The logic here is roughly the same as that found in process() except:
     *      + the ingress and prerouting steps are skipped. We do:
     *          - forwarding
     *          - post routing (empty right now)
     *          - emit new packet
     *      + drop actions in process() are an empty return here (we just don't
     *        emit the packet)
     *      + no wildcard match cloning or updating.
     *      + it does not return an action but, instead sends it emits the
     *        packet for simulation if successful.
     */
    def sendIPPacket(packet: IPv4, expiry: Long)
                    (implicit ec: ExecutionContext, actorSystem: ActorSystem) {
        def _sendIPPacket(outPort: RouterPort[_], rt: Route) {
            if (packet.getDestinationAddress == outPort.portAddr.addressAsInt) {
                /* should never happen: it means we are trying to send a packet
                 * to ourselves, probably means that somebody sent an IP packet
                 * with a forged source address belonging to this router.
                 */
                log.error("Router {} trying to send a packet {} to itself.",
                          id, packet)
                return
            }

            val eth = (new Ethernet()).setEtherType(IPv4.ETHERTYPE)
            eth.setPayload(packet)
            eth.setSourceMACAddress(outPort.portMac)

            val macFuture = getNextHopMac(outPort, rt,
                                packet.getDestinationAddress, expiry)
            macFuture onSuccess {
                case null =>
                    log.error("Failed to get MAC address to emit local packet")
                case mac =>
                    eth.setDestinationMACAddress(mac)
                    // Apply post-routing (egress) chain.
                    val egrMatch = WildcardMatches.fromEthernetPacket(eth)
                    val egrPktContext = new PacketContext(null, eth, 0, null)
                    egrPktContext.setOutputPort(outPort.id)
                    val postRoutingResult = Chain.apply(outFilter,
                                       egrPktContext, egrMatch, id, false)
                    if (postRoutingResult.action == RuleAction.ACCEPT) {
                        SimulationController.getRef(actorSystem).tell(
                            EmitGeneratedPacket(rt.nextHopPort, eth))
                    } else if (postRoutingResult.action != RuleAction.DROP &&
                               postRoutingResult.action != RuleAction.REJECT) {
                        log.error("Post-routing for {} returned an action " +
                                  "which was {}, not ACCEPT, DROP, or REJECT.",
                                  id, postRoutingResult.action)
                    }
            }
        }

        val ipMatch = (new WildcardMatch()).
                setNetworkDestination(packet.getDestinationAddress).
                setNetworkSource(packet.getSourceAddress)
        val rt: Route = loadBalancer.lookup(ipMatch)
        if (rt == null || rt.nextHop != Route.NextHop.PORT)
            return
        if (rt.nextHopPort == null)
            return

        getRouterPort(rt.nextHopPort, expiry) onSuccess {
            case null => log.error("Failed to get port to emit local packet")
            case outPort => _sendIPPacket(outPort, rt)
        }
    }

    /**
     * Determine whether a packet can trigger an ICMP error.  Per RFC 1812 sec.
     * 4.3.2.7, some packets should not trigger ICMP errors:
     *   1) Other ICMP errors.
     *   2) Invalid IP packets.
     *   3) Destined to IP bcast or mcast address.
     *   4) Destined to a link-layer bcast or mcast.
     *   5) With source network prefix zero or invalid source.
     *   6) Second and later IP fragments.
     *
     * @param ethPkt
     *            We wish to know whether this packet may trigger an ICMP error
     *            message.
     * @param egressPortId
     *            If known, this is the port that would have emitted the packet.
     *            It's used to determine whether the packet was addressed to an
     *            IP (local subnet) broadcast address.
     * @return True if-and-only-if the packet meets none of the above conditions
     *         - i.e. it can trigger an ICMP error message.
     */
     def canSendIcmp(ethPkt: Ethernet, outPort: RouterPort[_]) : Boolean = {
        var ipPkt: IPv4 = null
        ethPkt.getPayload match {
            case ip: IPv4 => ipPkt = ip
            case _ => return false
        }

        // Ignore ICMP errors.
        if (ipPkt.getProtocol() == ICMP.PROTOCOL_NUMBER) {
            ipPkt.getPayload match {
                case icmp: ICMP if icmp.isError =>
                    log.debug("Skipping generation of ICMP error for " +
                              "ICMP error packet")
                    return false
                case _ =>
            }
        }
        // TODO(pino): check the IP packet's validity - RFC1812 sec. 5.2.2
        // Ignore packets to IP mcast addresses.
        if (ipPkt.isMcast) {
            log.debug("Not generating ICMP Unreachable for packet to an IP "
                    + "multicast address.")
            return false
        }
        // Ignore packets sent to the local-subnet IP broadcast address of the
        // intended egress port.
        if (null != outPort) {
            if (ipPkt.isSubnetBcast(
                        outPort.portAddr.addressAsInt, outPort.nwLength)) {
                log.debug("Not generating ICMP Unreachable for packet to "
                        + "the subnet local broadcast address.")
                return false
            }
        }
        // Ignore packets to Ethernet broadcast and multicast addresses.
        if (ethPkt.isMcast) {
            log.debug("Not generating ICMP Unreachable for packet to "
                    + "Ethernet broadcast or multicast address.")
            return false
        }
        // Ignore packets with source network prefix zero or invalid source.
        // TODO(pino): See RFC 1812 sec. 5.3.7
        if (ipPkt.getSourceAddress() == 0xffffffff
                || ipPkt.getDestinationAddress() == 0xffffffff) {
            log.debug("Not generating ICMP Unreachable for all-hosts broadcast "
                    + "packet")
            return false
        }
        // TODO(pino): check this fragment offset
        // Ignore datagram fragments other than the first one.
        if (0 != (ipPkt.getFragmentOffset & 0x1fff)) {
            log.debug("Not generating ICMP Unreachable for IP fragment packet")
            return false
        }
        return true
    }

    private def buildIcmpError(icmpType: Char, icmpCode: Any,
                   forMatch: WildcardMatch, forPacket: Ethernet) : ICMP = {
        val pktHere = forMatch.apply(forPacket)
        var ipPkt: IPv4 = null
        pktHere.getPayload match {
            case ip: IPv4 => ipPkt = ip
            case _ => return null
        }

        icmpCode match {
            case c: ICMP.EXCEEDED_CODE if icmpType == ICMP.TYPE_TIME_EXCEEDED =>
                val icmp = new ICMP()
                icmp.setTimeExceeded(c, ipPkt)
                return icmp
            case c: ICMP.UNREACH_CODE if icmpType == ICMP.TYPE_UNREACH =>
                val icmp = new ICMP()
                icmp.setUnreachable(c, ipPkt)
                return icmp
            case _ =>
                return null
        }
    }

    /**
     * Send an ICMP error message.
     *
     * @param ingressMatch
     *            The wildcard match that caused the message to be generated
     * @param packet
     *            The original packet that started the simulation
     */
     def sendIcmpError(inPort: RouterPort[_], ingressMatch: WildcardMatch,
                       packet: Ethernet, icmpType: Char, icmpCode: Any)
                      (implicit ec: ExecutionContext,
                       actorSystem: ActorSystem) {
        // Check whether the original packet is allowed to trigger ICMP.
        if (inPort == null)
            return
        if (!canSendIcmp(packet, inPort))
            return
        // Build the ICMP packet from inside-out: ICMP, IPv4, Ethernet headers.
        val icmp = buildIcmpError(icmpType, icmpCode, ingressMatch, packet)
        if (icmp == null)
            return

        val ip = new IPv4()
        ip.setPayload(icmp)
        ip.setProtocol(ICMP.PROTOCOL_NUMBER)
        // The nwDst is the source of triggering IPv4 as seen by this router.
        ip.setDestinationAddress(ingressMatch.getNetworkSource)
        // The nwSrc is the address of the ingress port.
        ip.setSourceAddress(inPort.portAddr.addressAsInt)
        val eth = new Ethernet()
        eth.setPayload(ip)
        eth.setEtherType(IPv4.ETHERTYPE)
        eth.setSourceMACAddress(inPort.portMac)
        eth.setDestinationMACAddress(ingressMatch.getEthernetSource)

        /* log.debug("sendIcmpError from port {}, {} to {}", new Object[] {
                ingressMatch.getInputPortUUID,
                IPv4.fromIPv4Address(ip.getSourceAddress()),
                IPv4.fromIPv4Address(ip.getDestinationAddress()) }) */
        SimulationController.getRef(actorSystem) ! EmitGeneratedPacket(
            ingressMatch.getInputPortUUID, eth)
    }
}
