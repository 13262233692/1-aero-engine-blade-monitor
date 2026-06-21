package com.aero.blademonitor.buffer;

import com.aero.blademonitor.model.RawPacket;
import com.lmax.disruptor.EventFactory;

public class RawPacketEvent {
    private RawPacket packet;

    public RawPacket getPacket() {
        return packet;
    }

    public void setPacket(RawPacket packet) {
        this.packet = packet;
    }

    public void clear() {
        this.packet = null;
    }

    public static final EventFactory<RawPacketEvent> FACTORY = RawPacketEvent::new;
}
