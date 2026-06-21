package com.aero.blademonitor.model;

import lombok.Data;
import java.time.Instant;

@Data
public class RawPacket {
    private byte[] payload;
    private int length;
    private long timestamp;
    private Instant receiveTime;
    private String sourceAddress;
    private int sourcePort;

    public RawPacket() {
        this.timestamp = System.nanoTime();
        this.receiveTime = Instant.now();
    }

    public void setPayload(byte[] data, int len) {
        this.payload = new byte[len];
        System.arraycopy(data, 0, this.payload, 0, len);
        this.length = len;
    }
}
