/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.Ignore
import org.scalatest.junit.JUnitRunner

import com.midokura.midolman.DatapathController.PacketIn
import com.midokura.midolman.FlowController.{AddWildcardFlow,
                                             InvalidateFlowsByTag}
import com.midokura.midolman.datapath.FlowKeyVrnPort
import com.midokura.midolman.topology.VirtualTopologyActor.PortRequest
import com.midokura.midonet.cluster.data.{Bridge => ClusterBridge, Ports}
import com.midokura.midonet.cluster.data.host.Host
import com.midokura.packets.{IntIPv4, MAC, Packets}
import com.midokura.sdn.dp.{FlowMatches, FlowMatch, Packet}
import com.midokura.sdn.dp.flows.{FlowKeyInPort, FlowKeys}
import com.midokura.sdn.flows.WildcardMatches


@RunWith(classOf[JUnitRunner])
class BridgeSimulationTestCase extends MidolmanTestCase {

    override protected def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("datapath.max_flow_count", "10")
        super.fillConfig(config)
    }

    @Ignore
    def testPacketInBridgeSimulation() {

        val host = new Host(hostId()).setName("myself")
        clusterDataClient().hostsCreate(hostId(), host)

        val bridge = new ClusterBridge().setName("test")
        bridge.setId(clusterDataClient().bridgesCreate(bridge))

        val vifPort1 =
            clusterDataClient().portsCreate(Ports.materializedBridgePort(bridge))
        val vifPort2 =
            clusterDataClient().portsCreate(Ports.materializedBridgePort(bridge))

        clusterDataClient().hostsAddVrnPortMapping(hostId, vifPort1, "port1")
        clusterDataClient().hostsAddVrnPortMapping(hostId, vifPort2, "port2")

        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        val portNo = dpController().underlyingActor.localPorts("port1")
            .getPortNo
        val ethPkt = Packets.udp(
                MAC.fromString("02:11:22:33:44:10"),
                MAC.fromString("02:11:22:33:44:11"),
                IntIPv4.fromString("10.0.1.10"),
                IntIPv4.fromString("10.0.1.11"),
                10, 11, "My UDP packet".getBytes)

        val flowMatch = FlowMatches.fromEthernetPacket(ethPkt)
            .addKey(new FlowKeyInPort().setInPort(portNo))
        val dpPkt = new Packet()
            .setMatch(flowMatch)
            .setData(ethPkt.serialize())
        triggerPacketIn(dpPkt)

        val packetIn = requestOfType[PacketIn](dpProbe())

        packetIn should not be null
        packetIn.packet should not be null
        packetIn.wMatch should not be null

        val packetInMsg = requestOfType[PacketIn](simProbe())

        packetInMsg.wMatch should not be null
        packetInMsg.wMatch.getInputPortUUID should be(vifPort1)

        requestOfType[InvalidateFlowsByTag](flowProbe())
        requestOfType[AddWildcardFlow](flowProbe())

    }
}
