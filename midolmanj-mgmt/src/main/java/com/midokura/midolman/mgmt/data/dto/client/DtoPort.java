/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midolman.mgmt.data.dto.client;

import java.net.URI;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoPort {
    private UUID id = null;
    private UUID deviceId = null;
    private UUID inboundFilter = null;
    private UUID outboundFilter = null;
    private UUID vifId = null;
    private URI uri;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public UUID getInboundFilter() {
        return inboundFilter;
    }

    public void setInboundFilter(UUID inboundFilter) {
        this.inboundFilter = inboundFilter;
    }

    public UUID getOutboundFilter() {
        return outboundFilter;
    }

    public void setOutboundFilter(UUID outboundFilter) {
        this.outboundFilter = outboundFilter;
    }

    public UUID getVifId() {
        return vifId;
    }

    public void setVifId(UUID vifId) {
        this.vifId = vifId;
    }

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }
}
