package com.aero.blademonitor.udp;

import com.aero.blademonitor.buffer.BackpressureLevel;
import com.aero.blademonitor.buffer.DisruptorBufferManager;
import com.aero.blademonitor.buffer.RawPacketPool;
import com.aero.blademonitor.config.AppProperties;
import com.aero.blademonitor.model.RawPacket;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
@Component
public class NioUdpReceiver {

    private static final int BATCH_READ_SIZE = 64;
    private static final int MIN_AVAILABLE_THRESHOLD = 128;

    private final AppProperties appProperties;
    private final DisruptorBufferManager bufferManager;
    private final RawPacketPool packetPool;
    private final Executor udpReceiverExecutor;

    private DatagramChannel channel;
    private Selector selector;
    private Thread receiverThread;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final LongAdder packetsReceived = new LongAdder();
    private final LongAdder bytesReceived = new LongAdder();
    private final LongAdder icmpErrors = new LongAdder();
    private final LongAdder socketErrors = new LongAdder();

    private final AtomicLong lastReportCount = new AtomicLong(0);
    private final AtomicLong lastReportTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastBackpressureLevel = new AtomicLong(0);

    private final List<ByteBuffer> directBufferPool;
    private int bufferPoolIndex = 0;

    public NioUdpReceiver(
            AppProperties appProperties,
            DisruptorBufferManager bufferManager,
            RawPacketPool packetPool,
            @Qualifier("udpReceiverExecutor") Executor udpReceiverExecutor) {
        this.appProperties = appProperties;
        this.bufferManager = bufferManager;
        this.packetPool = packetPool;
        this.udpReceiverExecutor = udpReceiverExecutor;
        this.directBufferPool = new ArrayList<>(BATCH_READ_SIZE);
    }

    @PostConstruct
    public void start() {
        initBufferPool();
        udpReceiverExecutor.execute(this::runReceiverLoop);
    }

    private void initBufferPool() {
        int bufferSize = appProperties.getUdp().getBufferSize();
        for (int i = 0; i < BATCH_READ_SIZE; i++) {
            directBufferPool.add(ByteBuffer.allocateDirect(bufferSize));
        }
    }

    private void runReceiverLoop() {
        try {
            initChannel();
            running.set(true);
            receiverThread = Thread.currentThread();
            receiverThread.setName("nio-udp-receiver");
            receiverThread.setPriority(Thread.MAX_PRIORITY);

            log.info("NIO UDP Receiver started on port {}, buffer size: {}, SO_RCVBUF: {}KB",
                    appProperties.getUdp().getPort(),
                    appProperties.getUdp().getBufferSize(),
                    appProperties.getUdp().getSocketReceiveBuffer() / 1024);

            long timeoutMs = appProperties.getUdp().getSelectorTimeoutMs();
            long selectNowCount = 0;
            long lastSelectNowReset = System.currentTimeMillis();

            while (running.get() && !Thread.interrupted()) {
                try {
                    BackpressureLevel bpLevel = bufferManager.getCurrentLevel();

                    if (bpLevel == BackpressureLevel.CRITICAL) {
                        drainSocketToPreventICMP();
                        Thread.yield();
                        continue;
                    }

                    if (bpLevel == BackpressureLevel.HIGH) {
                        Thread.onSpinWait();
                    }

                    int readyCount;
                    if (bpLevel == BackpressureLevel.NORMAL || bpLevel == BackpressureLevel.LOW) {
                        readyCount = selector.select(timeoutMs);
                        selectNowCount = 0;
                    } else {
                        readyCount = selector.selectNow();
                        selectNowCount++;
                    }

                    if (selectNowCount > 10000 && System.currentTimeMillis() - lastSelectNowReset > 100) {
                        lastSelectNowReset = System.currentTimeMillis();
                        selectNowCount = 0;
                    }

                    if (readyCount == 0) {
                        continue;
                    }

                    Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
                    while (keyIterator.hasNext()) {
                        SelectionKey key = keyIterator.next();
                        keyIterator.remove();

                        if (key.isValid() && key.isReadable()) {
                            handleReadBatch();
                        }
                    }

                    if (bpLevel.ordinal() > lastBackpressureLevel.getAndSet(bpLevel.ordinal())) {
                        log.warn("Receiver entering backpressure level {} - slowing down ingestion",
                                bpLevel.name());
                    }

                } catch (PortUnreachableException e) {
                    handlePortUnreachable(e);
                } catch (IOException e) {
                    if (running.get()) {
                        handleIOException(e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("UDP Receiver fatal error", e);
        } finally {
            cleanup();
        }
    }

    private void initChannel() throws IOException {
        int socketRcvBuf = appProperties.getUdp().getSocketReceiveBuffer();

        channel = DatagramChannel.open();
        channel.configureBlocking(false);

        try {
            channel.setOption(StandardSocketOptions.SO_RCVBUF, socketRcvBuf);
        } catch (IOException e) {
            log.warn("Failed to set SO_RCVBUF to {}KB, using default", socketRcvBuf / 1024, e);
        }

        try {
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            channel.setOption(StandardSocketOptions.SO_REUSEPORT, true);
        } catch (UnsupportedOperationException e) {
            log.debug("SO_REUSEPORT not supported on this platform");
        } catch (IOException e) {
            log.warn("Failed to set socket options", e);
        }

        channel.bind(new InetSocketAddress(appProperties.getUdp().getPort()));
        selector = Selector.open();
        channel.register(selector, SelectionKey.OP_READ);

        int actualRcvBuf = channel.getOption(StandardSocketOptions.SO_RCVBUF);
        log.info("UDP socket initialized. Actual SO_RCVBUF: {}KB", actualRcvBuf / 1024);
    }

    private void handleReadBatch() {
        int batchCount = 0;
        long currentSeq = 0;

        try {
            while (batchCount < BATCH_READ_SIZE && running.get()) {
                ByteBuffer buffer = getNextBuffer();
                buffer.clear();

                InetSocketAddress source = (InetSocketAddress) channel.receive(buffer);

                if (source == null) {
                    break;
                }

                buffer.flip();
                int length = buffer.remaining();

                if (length <= 0) {
                    continue;
                }

                packetsReceived.increment();
                bytesReceived.add(length);

                if (!bufferManager.publish(copyBufferToPacket(buffer, source, currentSeq++))) {
                }

                batchCount++;
            }
        } catch (PortUnreachableException e) {
            handlePortUnreachable(e);
        } catch (IOException e) {
            handleIOException(e);
        }

        if (packetsReceived.sum() % 50000 == 0) {
            reportThroughput();
        }
    }

    private ByteBuffer getNextBuffer() {
        ByteBuffer buf = directBufferPool.get(bufferPoolIndex);
        bufferPoolIndex = (bufferPoolIndex + 1) % BATCH_READ_SIZE;
        return buf;
    }

    private RawPacket copyBufferToPacket(ByteBuffer buffer, InetSocketAddress source, long seq) {
        int length = buffer.remaining();
        RawPacket packet = packetPool.borrow();

        byte[] payload = packet.getPayload();
        if (payload == null || payload.length < length) {
            payload = new byte[length];
        }

        buffer.get(payload, 0, length);
        packet.setPayload(payload, length);
        packet.setTimestamp(System.nanoTime());
        packet.setReceiveTime(java.time.Instant.now());
        packet.setSourceAddress(source.getAddress().getHostAddress());
        packet.setSourcePort(source.getPort());

        return packet;
    }

    private void drainSocketToPreventICMP() {
        try {
            ByteBuffer buf = getNextBuffer();
            int drainCount = 0;
            while (drainCount < 256) {
                buf.clear();
                InetSocketAddress source = (InetSocketAddress) channel.receive(buf);
                if (source == null) break;

                packetsReceived.increment();
                bufferManager.getDroppedByCapacity();

                drainCount++;
            }

            if (drainCount > 0 && drainCount % 5000 == 0) {
                log.warn("Drained {} packets due to CRITICAL backpressure", drainCount);
            }
        } catch (Exception e) {
            log.debug("Drain exception", e);
        }
    }

    private void handlePortUnreachable(PortUnreachableException e) {
        icmpErrors.increment();

        if (icmpErrors.sum() % 100 == 1) {
            log.warn("PortUnreachableException (ICMP) received. This may indicate the remote side is not reachable. " +
                    "Count: {}, Message: {}", icmpErrors.sum(), e.getMessage());
        }

        try {
            if (channel != null && channel.isOpen()) {
                ByteBuffer dummy = ByteBuffer.allocate(1);
                dummy.put((byte) 0);
                dummy.flip();
                try {
                    channel.receive(dummy);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private void handleIOException(IOException e) {
        socketErrors.increment();
        if (socketErrors.sum() % 10 == 1) {
            log.warn("Socket IOException in receiver. Count: {}, Message: {}",
                    socketErrors.sum(), e.getMessage());
        }

        if (socketErrors.sum() > 100 && socketErrors.sum() % 50 == 0) {
            log.error("High socket error rate detected. Errors: {}, attempting recovery...",
                    socketErrors.sum());
            attemptChannelRecovery();
        }
    }

    private void attemptChannelRecovery() {
        try {
            if (selector != null) {
                selector.close();
            }
            if (channel != null) {
                channel.close();
            }
            Thread.sleep(100);
            initChannel();
            log.info("UDP channel recovery completed successfully");
            socketErrors.reset();
        } catch (Exception e) {
            log.error("Failed to recover UDP channel", e);
        }
    }

    private void reportThroughput() {
        long now = System.currentTimeMillis();
        long lastTime = lastReportTime.getAndSet(now);
        long lastCount = lastReportCount.get();
        long currentCount = packetsReceived.sum();
        lastReportCount.set(currentCount);

        long intervalMs = Math.max(1, now - lastTime);
        long deltaCount = currentCount - lastCount;
        double pps = (deltaCount * 1000.0) / intervalMs;
        double mbps = (bytesReceived.sumThenReset() * 8.0) / (intervalMs * 1000.0);

        BackpressureLevel bp = bufferManager.getCurrentLevel();
        long backlog = bufferManager.getCurrentBacklog();
        double fill = bufferManager.getFillRatio() * 100;

        log.info(String.format("UDP | PPS: %,.0f | Mbps: %.2f | Total: %,d | " +
                        "Backlog: %d (%.1f%%) | BP: %s | ICMP errors: %,d | Pool hit: %.1f%%",
                pps, mbps, currentCount,
                backlog, fill,
                bp.name(),
                icmpErrors.sum(),
                packetPool.getHitRate() * 100));
    }

    public long getPacketsReceived() {
        return packetsReceived.sum();
    }

    public long getIcmpErrors() { return icmpErrors.sum(); }
    public long getSocketErrors() { return socketErrors.sum(); }

    public double getCurrentPacketsPerSecond() {
        long now = System.currentTimeMillis();
        long intervalMs = Math.max(1, now - lastReportTime.get());
        long deltaCount = packetsReceived.sum() - lastReportCount.get();
        return (deltaCount * 1000.0) / intervalMs;
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (receiverThread != null) {
            receiverThread.interrupt();
        }
    }

    private void cleanup() {
        try {
            if (selector != null) {
                selector.wakeup();
                selector.close();
            }
            if (channel != null) {
                channel.close();
            }
            log.info("NIO UDP Receiver stopped. Total received: {:,} packets, ICMP errors: {}",
                    packetsReceived.sum(), icmpErrors.sum());
        } catch (IOException e) {
            log.warn("Error during cleanup", e);
        }
    }
}
