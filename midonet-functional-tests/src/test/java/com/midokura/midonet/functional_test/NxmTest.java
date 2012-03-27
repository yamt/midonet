/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.openflow.nxm.NxActionSetTunnelKey32;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnectionImpl;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midonet.functional_test.openflow.PrimaryController;
import com.midokura.midonet.functional_test.openflow.PrimaryController.PacketIn;
import com.midokura.midonet.functional_test.openflow.PrimaryController.Protocol;
import com.midokura.midonet.functional_test.topology.OvsBridge;
import com.midokura.midonet.functional_test.topology.TapWrapper;


import static org.junit.Assert.*;
import static org.junit.Assert.assertArrayEquals;

public class NxmTest {
    static OpenvSwitchDatabaseConnection ovsdb;
    static Protocol proto = Protocol.NXM;
    static UUID dummyID = new UUID(0, 0);
    static PacketHelper helper =
            new PacketHelper(MAC.fromString("02:00:00:aa:aa:01"),
                    IntIPv4.fromString("192.168.231.1"),
                    MAC.fromString("02:00:00:aa:aa:02"),
                    IntIPv4.fromString("192.168.231.2"));

    OvsBridge ovsBridge1;
    OvsBridge ovsBridge2;
    TapWrapper tap1;
    TapWrapper tap2;
    PrimaryController controller1;
    PrimaryController controller2;

    @BeforeClass
    public static void setUp() {
        ovsdb = new OpenvSwitchDatabaseConnectionImpl("Open_vSwitch",
                "127.0.0.1", 12344);
    }

    @After
    public void tearDown() {
        if (controller1 != null &&
                controller1.getStub() != null)
            controller1.getStub().close();
        if (controller2 != null &&
                controller2.getStub() != null)
            controller2.getStub().close();
        if (ovsBridge1 != null)
            ovsBridge1.remove();
        if (ovsBridge2 != null)
            ovsBridge2.remove();
        if (tap1 != null) {
            tap1.remove();
        }
        if (tap2 != null) {
            tap2.remove();
        }
    }

    @AfterClass
    public static void finalTearDown() {
        if (null != ovsdb)
            ovsdb.close();
    }

    public static OFMatch makeFlowMatch(OFMatch match) {
        MidoMatch flowMatch = new MidoMatch();
        flowMatch.setInputPort(match.getInputPort());
        flowMatch.setDataLayerDestination(match.getDataLayerDestination());
        flowMatch.setDataLayerSource(match.getDataLayerSource());
        flowMatch.setDataLayerType(match.getDataLayerType());
        flowMatch.setNetworkDestination(match.getNetworkDestination());
        flowMatch.setNetworkSource(match.getNetworkSource());
        return flowMatch;
    }

    @Test
    public void testArpNoTunnel() throws InterruptedException, IOException {
        if (ovsdb.hasBridge("nxm-br"))
            ovsdb.delBridge("nxm-br");
        // Create a single Controller.
        controller1 = new PrimaryController(8888, proto);
        ovsBridge1 = new OvsBridge(ovsdb, "nxm-br", "tcp:127.0.0.1:8888");
        // Create two ports on bridge1.
        tap1 = new TapWrapper("nxmtap1");
        ovsBridge1.addSystemPort(dummyID, tap1.getName());
        tap2 = new TapWrapper("nxmtap2");
        ovsBridge1.addSystemPort(dummyID, tap2.getName());

        Thread.sleep(5000);
        assertTrue(controller1.waitForBridge(ovsBridge1.getName()));
        assertTrue(controller1.waitForPort(tap1.getName()));
        assertTrue(controller1.waitForPort(tap2.getName()));

        byte[] arp = helper.makeArpRequest();
        assertTrue(tap1.send(arp));
        PacketIn pktIn = controller1.getNextPacket();
        assertNotNull(pktIn);
        assertEquals(controller1.getPortNum(tap1.getName()), pktIn.inPort);
        assertArrayEquals(arp, pktIn.packet);

        OFMatch match = new OFMatch();
        match.loadFromPacket(arp, pktIn.inPort);
        OFMatch flMatch = makeFlowMatch(match);
        // Send this flow to tap2.
        List<OFAction> actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(controller1.getPortNum(tap2.getName()),
                (short) 0));
        controller1.getStub().sendFlowModAdd(flMatch, 0, (short)0, (short)0,
                (short)0, pktIn.bufferId, false, false, false, actions);
        // If the packet was buffered in OVS, it should go directly to tap2.
        if (pktIn.bufferId != PrimaryController.UNBUFFERED_ID)
            assertArrayEquals(arp, tap2.recv());
        assertNull(tap1.recv());
        assertNull(tap2.recv());

        // Resend the same packet. It should go directly to tap2.
        assertTrue(tap1.send(arp));
        assertArrayEquals(arp, tap2.recv());
        assertNull(tap1.recv());
        assertNull(tap2.recv());
        assertNull(controller1.getNextPacket());
    }

    @Test
    public void testIpWithTunnel() throws IOException, InterruptedException {
        if (ovsdb.hasBridge("nxm-br1"))
            ovsdb.delBridge("nxm-br1");
        if (ovsdb.hasBridge("nxm-br2"))
            ovsdb.delBridge("nxm-br2");

        // Create two Controllers.
        controller1 = new PrimaryController(8889, proto);
        controller2 = new PrimaryController(8890, proto);
        // Create two OVS bridges
        ovsBridge1 = new OvsBridge(ovsdb, "nxm-br1", "tcp:127.0.0.1:8889");
        ovsBridge2 = new OvsBridge(ovsdb, "nxm-br2", "tcp:127.0.0.1:8890");

        // Create one port on each bridge.
        tap1 = new TapWrapper("nxmtap1");
        ovsBridge1.addSystemPort(dummyID, tap1.getName());
        tap2 = new TapWrapper("nxmtap2");
        ovsBridge2.addSystemPort(dummyID, tap2.getName());

        Thread.sleep(5000);
        assertTrue(controller1.waitForBridge(ovsBridge1.getName()));
        assertTrue(controller1.waitForPort(tap1.getName()));
        assertTrue(controller2.waitForBridge(ovsBridge2.getName()));
        assertTrue(controller2.waitForPort(tap2.getName()));

        // Create the Gre Ports for the tunnel between the bridges.
        // Setting the GreKey to 0 allows the key to be set flow-by-flow.
        int tunId = proto.equals(Protocol.NXM)? 0 : 5;
        ovsBridge1.addGrePort("nxmgre1", "127.0.0.1", "127.0.0.2", tunId);
        ovsBridge2.addGrePort("nxmgre2", "127.0.0.2", "127.0.0.1", tunId);
        assertTrue(controller1.waitForPort("nxmgre1"));
        assertTrue(controller2.waitForPort("nxmgre2"));

        byte[] ipPkt = helper.makeIcmpEchoRequest(helper.gwIp);
        assertTrue(tap1.send(ipPkt));
        PacketIn pktIn = controller1.getNextPacket();
        assertNotNull(pktIn);
        assertEquals(controller1.getPortNum(tap1.getName()), pktIn.inPort);
        assertArrayEquals(ipPkt, pktIn.packet);

        // Send this flow out of bridge1's GRE port.
        OFMatch match = new OFMatch();
        match.loadFromPacket(ipPkt, pktIn.inPort);
        OFMatch flMatch = makeFlowMatch(match);
        List<OFAction> actions = new ArrayList<OFAction>();
        // Any modifying actions must come before the output action.
        if (proto.equals(Protocol.NXM)) {
            tunId = 12345;
            actions.add(new NxActionSetTunnelKey32(tunId));
        }
        actions.add(new OFActionOutput(controller1.getPortNum("nxmgre1"),
                (short) 0));
        controller1.getStub().sendFlowModAdd(flMatch, 0, (short)0, (short)0,
                (short)0, pktIn.bufferId, false, false, false, actions);

        // The packet arrives at bridge2's GRE port and then to controller2.
        pktIn = controller2.getNextPacket();
        assertNotNull(pktIn);
        assertEquals(controller2.getPortNum("nxmgre2"), pktIn.inPort);
        if (proto.equals(Protocol.NXM))
            assertEquals(tunId, pktIn.tunnelId);
        assertArrayEquals(ipPkt, pktIn.packet);
        int bufferId = pktIn.bufferId;

        // TODO(pino): re-enable this code
        /*
        // Send the packet again to test the flow installed at controller1.
        assertTrue(tap1.send(ipPkt));
        // The packet again goes to controller2.
        pktIn = controller2.getNextPacket();
        assertNotNull(pktIn);
        assertEquals(controller2.getPortNum("nxmgre2"), pktIn.inPort);
        if (proto.equals(Protocol.NXM))
            assertEquals(tunId, pktIn.tunnelId);
        assertArrayEquals(ipPkt, pktIn.packet);
        */

        // Install a flow entry on bridge2 that forwards to tap2.
        match = new OFMatch();
        match.loadFromPacket(ipPkt, pktIn.inPort);
        flMatch = makeFlowMatch(match);
        actions = new ArrayList<OFAction>();
        actions.add(new OFActionOutput(controller2.getPortNum(tap2.getName()),
                (short) 0));
        if (proto.equals(Protocol.NXM))
            controller2.getStub().sendFlowModAdd(match, 0, (short)0, (short)0,
                    (short)0, pktIn.bufferId, false, false, false, actions,
                    tunId);
        else
            controller2.getStub().sendFlowModAdd(match, 0, (short)0, (short)0,
                    (short)0, pktIn.bufferId, false, false, false, actions);
        // If the packet was buffered in OVS, it should go directly to tap2.
        if (pktIn.bufferId != PrimaryController.UNBUFFERED_ID)
            assertArrayEquals(ipPkt, tap2.recv());

        // TODO(pino): re-enable this.
        /*
        // Also send the previous packet if it was buffered.
        if (bufferId != PrimaryController.UNBUFFERED_ID) {
            controller2.getStub().sendPacketOut(bufferId,
                    OFPort.OFPP_NONE.getValue(), actions, null);
            assertArrayEquals(ipPkt, tap2.recv());
        }
        */

        assertNull(tap1.recv());
        assertNull(tap2.recv());

        // TODO(pino): re-enable this.
        /*
        // Finally, resend the same packet from tap1. It should go directly
        // through the tunnel (GRE ports) and arrive at tap2.
        assertTrue(tap1.send(ipPkt));
        assertArrayEquals(ipPkt, tap2.recv());
        assertNull(tap1.recv());
        assertNull(tap2.recv());
        */
        assertNull(controller1.getNextPacket());
        assertNull(controller2.getNextPacket());
    }
}
