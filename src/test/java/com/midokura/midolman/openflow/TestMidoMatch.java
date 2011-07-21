/* Copyright 2011 Midokura Inc. */

package com.midokura.midolman.openflow;

import com.midokura.midolman.openflow.MidoMatch;

import junit.framework.TestCase;
import org.junit.Assert;
import org.junit.Test;
import org.openflow.protocol.OFMatch;


public class TestMidoMatch extends TestCase {

    @Test
    public void testDefaultCtor() {
        OFMatch mmatch = new MidoMatch();
        Assert.assertEquals(OFMatch.OFPFW_ALL, mmatch.getWildcards());
    }

    @Test
    public void testSetDlDest() {
        OFMatch mmatch = new MidoMatch();
        byte[] dlDest = { 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f };
        String dlDestStr = "0a:0b:0c:0d:0e:0f";
        mmatch.setDataLayerDestination(dlDest);
        Assert.assertArrayEquals(dlDest, mmatch.getDataLayerDestination());
        Assert.assertEquals(OFMatch.OFPFW_ALL & ~OFMatch.OFPFW_DL_DST,
                            mmatch.getWildcards());
        mmatch = new MidoMatch();
        mmatch.setDataLayerDestination(dlDestStr);
        Assert.assertArrayEquals(dlDest, mmatch.getDataLayerDestination());
        Assert.assertEquals(OFMatch.OFPFW_ALL & ~OFMatch.OFPFW_DL_DST,
                            mmatch.getWildcards());
    }

    @Test
    public void testSetDlSource() {
        OFMatch mmatch = new MidoMatch();
        byte[] dlSource = { 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f };
        String dlSourceStr = "0a:0b:0c:0d:0e:0f";
        mmatch.setDataLayerSource(dlSource);
        Assert.assertArrayEquals(dlSource, mmatch.getDataLayerSource());
        Assert.assertEquals(OFMatch.OFPFW_ALL & ~OFMatch.OFPFW_DL_SRC,
                            mmatch.getWildcards());
        mmatch = new MidoMatch();
        mmatch.setDataLayerSource(dlSourceStr);
        Assert.assertArrayEquals(dlSource, mmatch.getDataLayerSource());
        Assert.assertEquals(OFMatch.OFPFW_ALL & ~OFMatch.OFPFW_DL_SRC,
                            mmatch.getWildcards());
    }

    @Test
    public void testSetDlType() {
        OFMatch mmatch = new MidoMatch();
        short dlType = 0x11ee;
        mmatch.setDataLayerType(dlType);
        Assert.assertEquals(dlType, mmatch.getDataLayerType());
        Assert.assertEquals(OFMatch.OFPFW_ALL & ~OFMatch.OFPFW_DL_TYPE,
                            mmatch.getWildcards());
    }

    @Test
    public void testSetVlan() {
        OFMatch mmatch = new MidoMatch();
        short dlVlan = 0x11ee;
        mmatch.setDataLayerVirtualLan(dlVlan);
        Assert.assertEquals(dlVlan, mmatch.getDataLayerVirtualLan());
        Assert.assertEquals(OFMatch.OFPFW_ALL & ~OFMatch.OFPFW_DL_VLAN,
                            mmatch.getWildcards());
    }

    @Test
    public void testSetVlanPcp() {
        OFMatch mmatch = new MidoMatch();
        byte dlVlanPcp = -0x22;
        mmatch.setDataLayerVirtualLanPriorityCodePoint(dlVlanPcp);
        Assert.assertEquals(dlVlanPcp,
                            mmatch.getDataLayerVirtualLanPriorityCodePoint());
        Assert.assertEquals(OFMatch.OFPFW_ALL & ~OFMatch.OFPFW_DL_VLAN_PCP,
                            mmatch.getWildcards());
    }

    @Test
    public void testSetInputPort() {
        OFMatch mmatch = new MidoMatch();
        short inPort = 0x11ee;
        mmatch.setInputPort(inPort);
        Assert.assertEquals(inPort, mmatch.getInputPort());
        Assert.assertEquals(OFMatch.OFPFW_ALL & ~OFMatch.OFPFW_IN_PORT,
                            mmatch.getWildcards());
    }

    @Test
    public void testSetNwProto() {
        OFMatch mmatch = new MidoMatch();
        byte nwProto = 0x11;
        mmatch.setNetworkProtocol(nwProto);
        Assert.assertEquals(nwProto, mmatch.getNetworkProtocol());
        Assert.assertEquals(OFMatch.OFPFW_ALL & ~OFMatch.OFPFW_NW_PROTO,
                            mmatch.getWildcards());
    }

    @Test
    public void testSetNwTos() {
        OFMatch mmatch = new MidoMatch();
        byte nwTos = 0x11;
        mmatch.setNetworkTypeOfService(nwTos);
        Assert.assertEquals(nwTos, mmatch.getNetworkTypeOfService());
        Assert.assertEquals(OFMatch.OFPFW_ALL & ~OFMatch.OFPFW_NW_TOS,
                            mmatch.getWildcards());
    }

    @Test
    public void testSetTpDest() {
        OFMatch mmatch = new MidoMatch();
        short tpDest = 0x11ee;
        mmatch.setTransportDestination(tpDest);
        Assert.assertEquals(tpDest, mmatch.getTransportDestination());
        Assert.assertEquals(OFMatch.OFPFW_ALL & ~OFMatch.OFPFW_TP_DST,
                            mmatch.getWildcards());
    }

    @Test
    public void testSetTpSource() {
        OFMatch mmatch = new MidoMatch();
        short tpSource = 0x11ee;
        mmatch.setTransportSource(tpSource);
        Assert.assertEquals(tpSource, mmatch.getTransportSource());
        Assert.assertEquals(OFMatch.OFPFW_ALL & ~OFMatch.OFPFW_TP_SRC,
                            mmatch.getWildcards());
    }

}
