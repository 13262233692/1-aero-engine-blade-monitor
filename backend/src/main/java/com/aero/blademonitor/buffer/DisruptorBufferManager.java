package com.aero.blademonitor.buffer;

import com.aero.blademonitor.config.AppProperties;
import com.aero.blademonitor.model.RawPacket;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.Sequencer;
import com.lmax.disruptor.WorkHandler;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

@Slf4j
@Component
public class DisruptorBufferManager {

    public static final String GROUP_PARSER = "parser";
    public static final String GROUP_FFT = "fft";
    public static final String GROUP_BROADCAST = "broadcast";
    public static final String GROUP_PERSISTENCE = "persistence";

    private final AppProperties appProperties;
    private final ApplicationContext appContext;
    private final RawPacketPool packetPool;

    private Disruptor<RawPacketEvent> disruptor;
    private RingBuffer<RawPacketEvent> ringBuffer;

    private volatile BackpressureLevel currentLevel = BackpressureLevel.NORMAL;
    private final AtomicBoolean backpressureActive = new AtomicBoolean(false);
    private final AtomicLong lastBackpressureWarning = new AtomicLong(0);

    private final AtomicLong publishedCount = new AtomicLong(0);
    private final AtomicLong consumedByAllCount = new AtomicLong(0);
    private final AtomicLong droppedByBackpressure = new AtomicLong(0);
    private final AtomicLong droppedByCapacity = new AtomicLong(0);

    private final AtomicLong parserProcessed = new AtomicLong(0);
    private final AtomicLong fftProcessed = new AtomicLong(0);
    private final AtomicLong broadcastProcessed = new AtomicLong(0);
    private final AtomicLong persistenceProcessed = new AtomicLong(0);

    private Sequence parserSequence;
    private Sequence fftSequence;
    private Sequence broadcastSequence;
    private Sequence persistenceSequence;

    private ScheduledExecutorService monitorExecutor;
    private volatile boolean running = false;

    private Consumer<List<RawPacket>> parserConsumer;
    private Consumer<List<RawPacket>> fftConsumer;
    private Consumer<List<RawPacket>> broadcastConsumer;
    private Consumer<List<RawPacket>> persistenceConsumer;

    @Autowired
    public DisruptorBufferManager(
            AppProperties appProperties,
            ApplicationContext appContext,
            RawPacketPool packetPool,
            @Qualifier("signalProcessingExecutor") Executor processingExecutor) {
        this.appProperties = appProperties;
        this.appContext = appContext;
        this.packetPool = packetPool;
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
                    Thread t = new Thread(r, "disruptor-main");
                    t.setDaemon(true);
                    return t;
                },
                ProducerType.SINGLE,
                new com.lmax.disruptor.BusySpinWaitStrategy()
        );

        disruptor.setDefaultExceptionHandler(createExceptionHandler());

        ringBuffer = disruptor.getRingBuffer();
        setupMultiConsumerPipeline();

        monitorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "disruptor-monitor");
            t.setDaemon(true);
            return t;
        });

        monitorExecutor.scheduleAtFixedRate(
                this::monitorBackpressure,
                100,
                100,
                TimeUnit.MILLISECONDS
        );

        disruptor.start();
        running = true;

        log.info("Disruptor multi-consumer pipeline initialized. Buffer size: {}, Backpressure levels: NORMAL→LOW(30%)→MEDIUM(60%)→HIGH(90%)→CRITICAL(99%)",
                adjustedSize);
    }

    private void setupMultiConsumerPipeline() {
        parserSequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
        fftSequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
        broadcastSequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
        persistenceSequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

        ringBuffer.addGatingSequences(parserSequence, fftSequence, broadcastSequence, persistenceSequence);

        startBatchConsumer(GROUP_PARSER, parserSequence, parserProcessed,
                new SequenceBarrier[] { ringBuffer.newBarrier() },
                128, () -> parserConsumer);

        startBatchConsumer(GROUP_FFT, fftSequence, fftProcessed,
                new SequenceBarrier[] { ringBuffer.newBarrier(parserSequence) },
                64, () -> fftConsumer);

        startBatchConsumer(GROUP_BROADCAST, broadcastSequence, broadcastProcessed,
                new SequenceBarrier[] { ringBuffer.newBarrier(parserSequence) },
                256, () -> broadcastConsumer);

        startBatchConsumer(GROUP_PERSISTENCE, persistenceSequence, persistenceProcessed,
                new SequenceBarrier[] { ringBuffer.newBarrier(parserSequence) },
                1024, () -> persistenceConsumer);
    }

    private void startBatchConsumer(
            String groupName,
            Sequence sequence,
            AtomicLong counter,
            SequenceBarrier[] barriersToTrack,
            int batchSize,
            java.util.function.Supplier<Consumer<List<RawPacket>>> consumerSupplier) {

        Thread consumerThread = new Thread(() -> {
            Thread.currentThread().setName("disruptor-" + groupName);
            long nextSequence = sequence.get() + 1L;
            java.util.ArrayList<RawPacket> batch = new java.util.ArrayList<>(batchSize);
            RawPacketPool localPool = packetPool;

            while (running && !Thread.interrupted()) {
                try {
                    SequenceBarrier barrier = barriersToTrack[0];
                    long availableSequence = barrier.waitFor(nextSequence);

                    while (nextSequence <= availableSequence) {
                        try {
                            RawPacketEvent event = ringBuffer.get(nextSequence);
                            RawPacket packet = event.getPacket();

                            if (packet != null) {
                                batch.add(packet);
                            }

                            if (batch.size() >= batchSize) {
                                processBatch(groupName, batch, consumerSupplier, counter, localPool);
                                batch.clear();
                            }

                            nextSequence++;
                        } catch (Exception e) {
                            log.error("Error processing event in {} at sequence {}",
                                    groupName, nextSequence, e);
                            nextSequence++;
                        }
                    }

                    if (!batch.isEmpty()) {
                        processBatch(groupName, batch, consumerSupplier, counter, localPool);
                        batch.clear();
                    }

                    sequence.set(availableSequence);
                    consumedByAllCount.set(availableSequence);

                } catch (com.lmax.disruptor.AlertException e) {
                    if (!running) break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Unexpected error in {} consumer", groupName, e);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (!batch.isEmpty()) {
                for (RawPacket p : batch) {
                    localPool.recycle(p);
                }
            }
            log.info("Disruptor consumer [{}] stopped. Total processed: {}",
                    groupName, counter.get());
        });

        consumerThread.setDaemon(true);
        consumerThread.start();
    }

    private void processBatch(
            String groupName,
            java.util.ArrayList<RawPacket> batch,
            java.util.function.Supplier<Consumer<List<RawPacket>>> consumerSupplier,
            AtomicLong counter,
            RawPacketPool localPool) {

        try {
            Consumer<List<RawPacket>> consumer = consumerSupplier.get();
            if (consumer != null) {
                consumer.accept(batch);
            }
        } catch (Exception e) {
            log.error("Batch processing error in consumer [{}]", groupName, e);
        } finally {
            counter.addAndGet(batch.size());
            for (RawPacket p : batch) {
                localPool.recycle(p);
            }
        }
    }

    public boolean publish(RawPacket packet) {
        if (!running || ringBuffer == null || packet == null) {
            packetPool.recycle(packet);
            return false;
        }

        long currentPublished = publishedCount.get();

        BackpressureLevel level = currentLevel;
        if (level.shouldDrop(currentPublished)) {
            packetPool.recycle(packet);
            droppedByBackpressure.incrementAndGet();
            return false;
        }

        try {
            long sequence = ringBuffer.tryNext();
            try {
                RawPacketEvent event = ringBuffer.get(sequence);
                event.setPacket(packet);
            } finally {
                ringBuffer.publish(sequence);
                publishedCount.incrementAndGet();
            }
            return true;
        } catch (com.lmax.disruptor.InsufficientCapacityException e) {
            droppedByCapacity.incrementAndGet();
            packetPool.recycle(packet);
            return false;
        }
    }

    private void monitorBackpressure() {
        if (ringBuffer == null) return;

        long cursor = ringBuffer.getCursor();
        long minConsumer = Long.MAX_VALUE;
        for (Sequence s : new Sequence[] { parserSequence, fftSequence, broadcastSequence, persistenceSequence }) {
            if (s != null) {
                minConsumer = Math.min(minConsumer, s.get());
            }
        }

        long backlog = cursor - minConsumer;
        double fillRatio = (double) backlog / ringBuffer.getBufferSize();

        BackpressureLevel newLevel = BackpressureLevel.fromFillRatio(fillRatio);
        if (newLevel != currentLevel) {
            currentLevel = newLevel;
            backpressureActive.set(newLevel != BackpressureLevel.NORMAL);

            long now = System.currentTimeMillis();
            if (now - lastBackpressureWarning.get() > 1000 || newLevel == BackpressureLevel.CRITICAL) {
                lastBackpressureWarning.set(now);
                log.warn(String.format("BACKPRESSURE [%s] - Fill: %.1f%% (%s), Backlog: %d, Drop rate: %s",
                        newLevel.name(),
                        fillRatio * 100,
                        newLevel.getDescription(),
                        backlog,
                        newLevel.getDropModulo() == 0 ? "none" : "1/" + newLevel.getDropModulo()));
            }
        }
    }

    private ExceptionHandler<RawPacketEvent> createExceptionHandler() {
        return new ExceptionHandler<>() {
            @Override
            public void handleEventException(Throwable ex, long sequence, RawPacketEvent event) {
                log.error("Disruptor event exception at sequence {}", sequence, ex);
                if (event != null && event.getPacket() != null) {
                    packetPool.recycle(event.getPacket());
                    event.clear();
                }
            }

            @Override
            public void handleOnStartException(Throwable ex) {
                log.error("Disruptor start exception", ex);
            }

            @Override
            public void handleOnShutdownException(Throwable ex) {
                log.error("Disruptor shutdown exception", ex);
            }
        };
    }

    public void setParserConsumer(Consumer<List<RawPacket>> c) { this.parserConsumer = c; }
    public void setFftConsumer(Consumer<List<RawPacket>> c) { this.fftConsumer = c; }
    public void setBroadcastConsumer(Consumer<List<RawPacket>> c) { this.broadcastConsumer = c; }
    public void setPersistenceConsumer(Consumer<List<RawPacket>> c) { this.persistenceConsumer = c; }

    public long getPublishedCount() { return publishedCount.get(); }
    public long getDroppedByBackpressure() { return droppedByBackpressure.get(); }
    public long getDroppedByCapacity() { return droppedByCapacity.get(); }
    public long getTotalDropped() { return droppedByBackpressure.get() + droppedByCapacity.get(); }

    public long getParserProcessed() { return parserProcessed.get(); }
    public long getFftProcessed() { return fftProcessed.get(); }
    public long getBroadcastProcessed() { return broadcastProcessed.get(); }
    public long getPersistenceProcessed() { return persistenceProcessed.get(); }

    public BackpressureLevel getCurrentLevel() { return currentLevel; }
    public double getFillRatio() {
        if (ringBuffer == null) return 0;
        long cursor = ringBuffer.getCursor();
        long min = parserSequence != null ? parserSequence.get() : cursor;
        return (double) (cursor - min) / ringBuffer.getBufferSize();
    }
    public long getCurrentBacklog() {
        if (ringBuffer == null) return 0;
        long cursor = ringBuffer.getCursor();
        long min = Long.MAX_VALUE;
        for (Sequence s : new Sequence[] { parserSequence, fftSequence, broadcastSequence, persistenceSequence }) {
            if (s != null) min = Math.min(min, s.get());
        }
        return cursor - min;
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (monitorExecutor != null) {
            monitorExecutor.shutdownNow();
        }
        if (disruptor != null) {
            try {
                disruptor.shutdown(3, TimeUnit.SECONDS);
                log.info("Disruptor buffer shutdown complete");
            } catch (Exception e) {
                log.warn("Disruptor shutdown timeout, forcing halt");
                disruptor.halt();
            }
        }
    }

    public static class BatchHandlerAdapter implements EventHandler<RawPacketEvent> {
        private final Consumer<RawPacket> consumer;
        private final AtomicLong counter;

        public BatchHandlerAdapter(Consumer<RawPacket> consumer, AtomicLong counter) {
            this.consumer = consumer;
            this.counter = counter;
        }

        @Override
        public void onEvent(RawPacketEvent event, long sequence, boolean endOfBatch) {
            try {
                if (event.getPacket() != null) {
                    consumer.accept(event.getPacket());
                    counter.incrementAndGet();
                }
            } finally {
                event.clear();
            }
        }
    }

    public static class WorkHandlerAdapter implements WorkHandler<RawPacketEvent> {
        private final Consumer<RawPacket> consumer;
        private final AtomicLong counter;

        public WorkHandlerAdapter(Consumer<RawPacket> consumer, AtomicLong counter) {
            this.consumer = consumer;
            this.counter = counter;
        }

        @Override
        public void onEvent(RawPacketEvent event) {
            try {
                if (event.getPacket() != null) {
                    consumer.accept(event.getPacket());
                    counter.incrementAndGet();
                }
            } finally {
                event.clear();
            }
        }
    }
}
