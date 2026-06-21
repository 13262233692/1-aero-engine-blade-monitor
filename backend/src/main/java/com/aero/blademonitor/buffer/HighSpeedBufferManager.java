package com.aero.blademonitor.buffer;

import com.aero.blademonitor.config.AppProperties;
import com.aero.blademonitor.model.RawPacket;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
@Component
public class HighSpeedBufferManager {

    private final AppProperties appProperties;
    private final Executor signalProcessingExecutor;

    private Disruptor<RawPacketEvent> disruptor;
    private RingBuffer<RawPacketEvent> ringBuffer;

    private final AtomicLong publishedCount = new AtomicLong(0);
    private final AtomicLong consumedCount = new AtomicLong(0);
    private final AtomicLong droppedCount = new AtomicLong(0);

    private volatile Consumer<RawPacket> packetConsumer;
    private volatile boolean running = false;

    public HighSpeedBufferManager(
            AppProperties appProperties,
            @Qualifier("signalProcessingExecutor") Executor signalProcessingExecutor) {
        this.appProperties = appProperties;
        this.signalProcessingExecutor = signalProcessingExecutor;
    }

    @PostConstruct
    public void init() {
        int bufferSize = appProperties.getBuffer().getRingBufferSize();
        int adjustedSize = 1;
        while (adjustedSize < bufferSize) adjustedSize <<= 1;

        disruptor = new Disruptor<>(
                RawPacketEvent.FACTORY,
                adjustedSize,
                r -> {
                    Thread t = new Thread(r, "disruptor-consumer");
                    t.setDaemon(true);
                    return t;
                },
                ProducerType.SINGLE,
                new com.lmax.disruptor.BlockingWaitStrategy()
        );

        disruptor.handleEventsWith((event, sequence, endOfBatch) -> {
            try {
                RawPacket packet = event.getPacket();
                if (packet != null && packetConsumer != null) {
                    packetConsumer.accept(packet);
                    consumedCount.incrementAndGet();
                }
            } catch (Exception e) {
                log.error("Disruptor event handling error", e);
            } finally {
                event.clear();
            }
        });

        disruptor.setDefaultExceptionHandler(new com.lmax.disruptor.ExceptionHandler<>() {
            @Override
            public void handleEventException(Throwable ex, long sequence, RawPacketEvent event) {
                log.error("Disruptor event exception at sequence {}", sequence, ex);
            }

            @Override
            public void handleOnStartException(Throwable ex) {
                log.error("Disruptor start exception", ex);
            }

            @Override
            public void handleOnShutdownException(Throwable ex) {
                log.error("Disruptor shutdown exception", ex);
            }
        });

        ringBuffer = disruptor.start();
        running = true;
        log.info("High-speed Disruptor buffer initialized with size: {}", adjustedSize);
    }

    public void setPacketConsumer(Consumer<RawPacket> consumer) {
        this.packetConsumer = consumer;
    }

    public boolean publish(RawPacket packet) {
        if (!running || ringBuffer == null) return false;

        long availableCapacity = ringBuffer.remainingCapacity();
        if (availableCapacity < 1) {
            droppedCount.incrementAndGet();
            return false;
        }

        try {
            long sequence = ringBuffer.next();
            RawPacketEvent event = ringBuffer.get(sequence);
            event.setPacket(packet);
            ringBuffer.publish(sequence);
            publishedCount.incrementAndGet();
            return true;
        } catch (Exception e) {
            log.warn("Failed to publish packet to buffer", e);
            droppedCount.incrementAndGet();
            return false;
        }
    }

    public int getCurrentBufferSize() {
        if (ringBuffer == null) return 0;
        return (int) (ringBuffer.getCursor() - ringBuffer.getMinimumGatingSequence());
    }

    public long getPublishedCount() { return publishedCount.get(); }
    public long getConsumedCount() { return consumedCount.get(); }
    public long getDroppedCount() { return droppedCount.get(); }

    public void resetCounters() {
        publishedCount.set(0);
        consumedCount.set(0);
        droppedCount.set(0);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (disruptor != null) {
            try {
                disruptor.shutdown(2, java.util.concurrent.TimeUnit.SECONDS);
                log.info("Disruptor buffer shutdown complete");
            } catch (Exception e) {
                log.warn("Disruptor shutdown timeout, forcing halt");
                disruptor.halt();
            }
        }
    }
}
