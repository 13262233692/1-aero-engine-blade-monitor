package com.aero.blademonitor.udp;

import com.aero.blademonitor.buffer.HighSpeedBufferManager;
import com.aero.blademonitor.config.AppProperties;
import com.aero.blademonitor.model.RawPacket;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class NioUdpReceiver {

    private final AppProperties appProperties;
    private final HighSpeedBufferManager bufferManager;
    private final Executor udpReceiverExecutor;

    private DatagramChannel channel;
    private Selector selector;
    private Thread receiverThread;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong packetsReceived = new AtomicLong(0);
    private final AtomicLong lastReportCount = new AtomicLong(0);
    private final AtomicLong lastReportTime = new AtomicLong(System.currentTimeMillis());

    private ByteBuffer directBuffer;

    public NioUdpReceiver(
            AppProperties appProperties,
            HighSpeedBufferManager bufferManager,
            @Qualifier("udpReceiverExecutor") Executor udpReceiverExecutor) {
        this.appProperties = appProperties;
        this.bufferManager = bufferManager;
        this.udpReceiverExecutor = udpReceiverExecutor;
    }

    @PostConstruct
    public void start() {
        udpReceiverExecutor.execute(this::runReceiverLoop);
    }

    private void runReceiverLoop() {
        try {
            initChannel();
            running.set(true);
            receiverThread = Thread.currentThread();
            receiverThread.setName("nio-udp-receiver");

            log.info("NIO UDP Receiver started on port {}, buffer size: {}",
                    appProperties.getUdp().getPort(),
                    appProperties.getUdp().getBufferSize());

            long timeoutMs = appProperties.getUdp().getSelectorTimeoutMs();

            while (running.get() && !Thread.interrupted()) {
                try {
                    int readyCount = selector.select(timeoutMs);
                    if (readyCount == 0) {
                        continue;
                    }

                    Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
                    while (keyIterator.hasNext()) {
                        SelectionKey key = keyIterator.next();
                        keyIterator.remove();

                        if (key.isValid() && key.isReadable()) {
                            handleRead();
                        }
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        log.error("Selector loop error", e);
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
        int bufferSize = appProperties.getUdp().getBufferSize();
        int socketRcvBuf = appProperties.getUdp().getSocketReceiveBuffer();

        directBuffer = ByteBuffer.allocateDirect(bufferSize);

        channel = DatagramChannel.open();
        channel.configureBlocking(false);
        channel.setOption(StandardSocketOptions.SO_RCVBUF, socketRcvBuf);
        channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        channel.bind(new InetSocketAddress(appProperties.getUdp().getPort()));

        selector = Selector.open();
        channel.register(selector, SelectionKey.OP_READ);
    }

    private void handleRead() {
        try {
            directBuffer.clear();
            InetSocketAddress source = (InetSocketAddress) channel.receive(directBuffer);

            if (source == null) {
                return;
            }

            directBuffer.flip();
            int length = directBuffer.remaining();

            if (length <= 0) {
                return;
            }

            byte[] payload = new byte[length];
            directBuffer.get(payload);

            RawPacket packet = new RawPacket();
            packet.setPayload(payload, length);
            packet.setSourceAddress(source.getAddress().getHostAddress());
            packet.setSourcePort(source.getPort());

            bufferManager.publish(packet);

            long count = packetsReceived.incrementAndGet();
            if (count % 10000 == 0) {
                reportThroughput(count);
            }
        } catch (IOException e) {
            log.warn("Error reading UDP packet", e);
        }
    }

    private void reportThroughput(long currentCount) {
        long now = System.currentTimeMillis();
        long lastTime = lastReportTime.getAndSet(now);
        long lastCount = lastReportCount.getAndSet(currentCount);

        long intervalMs = Math.max(1, now - lastTime);
        long deltaCount = currentCount - lastCount;
        double pps = (deltaCount * 1000.0) / intervalMs;

        log.info("UDP throughput: {} packets/s | total: {} | buffer: {}",
                String.format("%,.0f", pps),
                String.format("%,d", currentCount),
                bufferManager.getCurrentBufferSize());
    }

    public long getPacketsReceived() {
        return packetsReceived.get();
    }

    public double getCurrentPacketsPerSecond() {
        long now = System.currentTimeMillis();
        long intervalMs = Math.max(1, now - lastReportTime.get());
        long deltaCount = packetsReceived.get() - lastReportCount.get();
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
            if (channel != null) {
                channel.close();
            }
            if (selector != null) {
                selector.close();
            }
            log.info("NIO UDP Receiver stopped");
        } catch (IOException e) {
            log.warn("Error during cleanup", e);
        }
    }
}
