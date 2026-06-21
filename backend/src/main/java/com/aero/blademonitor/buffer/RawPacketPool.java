package com.aero.blademonitor.buffer;

import com.aero.blademonitor.model.RawPacket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class RawPacketPool {

    private static final int DEFAULT_POOL_SIZE = 8192;
    private static final int MAX_POOLED_PACKET_SIZE = 8192;

    private final ConcurrentLinkedQueue<RawPacket> pool;
    private final int maxPoolSize;

    private final AtomicInteger poolHits = new AtomicInteger(0);
    private final AtomicInteger poolMisses = new AtomicInteger(0);
    private final AtomicInteger totalCreated = new AtomicInteger(0);
    private final AtomicLong totalRecycled = new AtomicLong(0);

    public RawPacketPool() {
        this(DEFAULT_POOL_SIZE);
    }

    public RawPacketPool(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
        this.pool = new ConcurrentLinkedQueue<>();
        prewarm(Math.min(maxPoolSize / 4, 1024));
        log.info("RawPacketPool initialized with max size: {}, prewarmed: {}",
                maxPoolSize, Math.min(maxPoolSize / 4, 1024));
    }

    private void prewarm(int count) {
        for (int i = 0; i < count; i++) {
            pool.offer(createNewPacket());
            totalCreated.incrementAndGet();
        }
    }

    private RawPacket createNewPacket() {
        RawPacket packet = new RawPacket();
        packet.setPayload(new byte[MAX_POOLED_PACKET_SIZE], 0);
        return packet;
    }

    public RawPacket borrow() {
        RawPacket packet = pool.poll();
        if (packet != null) {
            poolHits.incrementAndGet();
            return packet;
        }
        poolMisses.incrementAndGet();
        RawPacket newPacket = createNewPacket();
        totalCreated.incrementAndGet();
        return newPacket;
    }

    public void recycle(RawPacket packet) {
        if (packet == null) return;

        if (pool.size() < maxPoolSize) {
            packet.setTimestamp(0);
            packet.setSourceAddress(null);
            packet.setSourcePort(0);
            if (packet.getPayload() != null && packet.getPayload().length >= MAX_POOLED_PACKET_SIZE) {
                packet.setLength(0);
            }
            pool.offer(packet);
            totalRecycled.incrementAndGet();
        }
    }

    public void clear() {
        pool.clear();
    }

    public int getPoolSize() {
        return pool.size();
    }

    public int getTotalCreated() { return totalCreated.get(); }
    public long getTotalRecycled() { return totalRecycled.get(); }
    public int getPoolHits() { return poolHits.get(); }
    public int getPoolMisses() { return poolMisses.get(); }

    public double getHitRate() {
        int hits = poolHits.get();
        int misses = poolMisses.get();
        int total = hits + misses;
        return total > 0 ? (double) hits / total : 0.0;
    }

    public PoolStats getStats() {
        return new PoolStats(
                pool.size(),
                maxPoolSize,
                totalCreated.get(),
                totalRecycled.get(),
                poolHits.get(),
                poolMisses.get(),
                getHitRate()
        );
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class PoolStats {
        private int currentSize;
        private int maxSize;
        private int totalCreated;
        private long totalRecycled;
        private int poolHits;
        private int poolMisses;
        private double hitRate;
    }
}
