package com.aero.blademonitor.persistence;

import com.aero.blademonitor.model.BladeDataFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

@Slf4j
@Component
public class AsyncPersistenceService {

    @Value("${app.persistence.enabled:false}")
    private boolean persistenceEnabled;

    @Value("${app.persistence.batch-size:1000}")
    private int batchSize;

    @Value("${app.persistence.max-queue-size:100000}")
    private int maxQueueSize;

    @Value("${app.persistence.flush-interval-ms:500}")
    private long flushIntervalMs;

    @Value("${app.persistence.rpm-threshold:1000}")
    private double rpmThreshold;

    private ExecutorService persistenceExecutor;
    private BlockingQueue<BladeDataFrame> persistenceQueue;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong totalEnqueued = new AtomicLong(0);
    private final AtomicLong totalPersisted = new AtomicLong(0);
    private final AtomicLong totalDropped = new AtomicLong(0);
    private final AtomicLong thresholdEventsCaptured = new AtomicLong(0);
    private final AtomicLong totalBatches = new AtomicLong(0);

    private final List<PersistenceListener> listeners = new ArrayList<>();

    @PostConstruct
    public void init() {
        if (!persistenceEnabled) {
            log.info("Async persistence service is DISABLED");
            return;
        }

        persistenceQueue = new ArrayBlockingQueue<>(maxQueueSize);
        persistenceExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "async-persistence");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        running.set(true);
        persistenceExecutor.submit(this::persistenceLoop);

        log.info("Async persistence service started. Batch size: {}, Queue size: {}, Flush interval: {}ms, RPM threshold: {}",
                batchSize, maxQueueSize, flushIntervalMs, rpmThreshold);
    }

    private void persistenceLoop() {
        List<BladeDataFrame> batch = new ArrayList<>(batchSize);
        long lastFlushTime = System.currentTimeMillis();

        while (running.get() && !Thread.interrupted()) {
            try {
                BladeDataFrame frame = persistenceQueue.poll(100, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    batch.add(frame);
                }

                long now = System.currentTimeMillis();
                boolean shouldFlush = batch.size() >= batchSize
                        || (now - lastFlushTime >= flushIntervalMs && !batch.isEmpty());

                if (shouldFlush) {
                    flushBatch(batch);
                    batch.clear();
                    lastFlushTime = now;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Persistence loop error", e);
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
        }

        if (!batch.isEmpty()) {
            flushBatch(batch);
        }

        log.info("Persistence loop stopped. Total persisted: {}, Total enqueued: {}, Dropped: {}",
                totalPersisted.get(), totalEnqueued.get(), totalDropped.get());
    }

    private void flushBatch(List<BladeDataFrame> batch) {
        if (batch.isEmpty()) return;

        int count = batch.size();
        try {
            long startNs = System.nanoTime();

            writeToStorage(batch);

            long latencyNs = System.nanoTime() - startNs;
            totalPersisted.addAndGet(count);
            totalBatches.incrementAndGet();

            notifyListenersBatchComplete(batch, latencyNs);

            if (totalBatches.get() % 100 == 0) {
                log.debug(String.format("Persisted batch: %d records, latency: %.2fms",
                        count, latencyNs / 1_000_000.0));
            }

        } catch (Exception e) {
            log.error("Failed to persist batch of {} records", count, e);
        }
    }

    private void writeToStorage(List<BladeDataFrame> batch) {
        for (BladeDataFrame frame : batch) {
            try {
                persistSingleFrame(frame);
            } catch (Exception e) {
                log.warn("Failed to persist frame", e);
            }
        }
    }

    private void persistSingleFrame(BladeDataFrame frame) {
    }

    public boolean enqueue(BladeDataFrame frame) {
        if (!persistenceEnabled || frame == null) {
            return false;
        }

        boolean isThresholdEvent = frame.getRpm() >= rpmThreshold;

        if (isThresholdEvent) {
            thresholdEventsCaptured.incrementAndGet();
        }

        if (persistenceQueue.offer(frame)) {
            totalEnqueued.incrementAndGet();
            return true;
        } else {
            totalDropped.incrementAndGet();

            if (totalDropped.get() % 1000 == 0) {
                log.warn("Persistence queue overflow! Dropped {} records. Queue size: {}",
                        totalDropped.get(), persistenceQueue.size());
            }
            return false;
        }
    }

    public void enqueueBatch(List<BladeDataFrame> frames) {
        if (!persistenceEnabled || frames == null || frames.isEmpty()) {
            return;
        }

        for (BladeDataFrame frame : frames) {
            if (frame != null && frame.isValid()) {
                enqueue(frame);
            }
        }
    }

    public void addListener(PersistenceListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void notifyListenersBatchComplete(List<BladeDataFrame> batch, long latencyNs) {
        for (PersistenceListener listener : listeners) {
            try {
                listener.onBatchPersisted(batch, latencyNs);
            } catch (Exception e) {
                log.warn("Persistence listener error", e);
            }
        }
    }

    public boolean isPersistenceEnabled() {
        return persistenceEnabled;
    }

    public long getTotalEnqueued() { return totalEnqueued.get(); }
    public long getTotalPersisted() { return totalPersisted.get(); }
    public long getTotalDropped() { return totalDropped.get(); }
    public long getThresholdEventsCaptured() { return thresholdEventsCaptured.get(); }
    public int getQueueSize() { return persistenceQueue != null ? persistenceQueue.size() : 0; }

    public PersistenceStats getStats() {
        return new PersistenceStats(
                persistenceEnabled,
                totalEnqueued.get(),
                totalPersisted.get(),
                totalDropped.get(),
                thresholdEventsCaptured.get(),
                getQueueSize(),
                maxQueueSize,
                totalBatches.get()
        );
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (persistenceExecutor != null) {
            persistenceExecutor.shutdown();
            try {
                if (!persistenceExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    persistenceExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                persistenceExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("Async persistence service shutdown complete");
    }

    public interface PersistenceListener {
        void onBatchPersisted(List<BladeDataFrame> batch, long latencyNs);
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class PersistenceStats {
        private boolean enabled;
        private long totalEnqueued;
        private long totalPersisted;
        private long totalDropped;
        private long thresholdEventsCaptured;
        private int currentQueueSize;
        private int maxQueueSize;
        private long totalBatches;

        public double getDropRate() {
            long total = totalEnqueued + totalDropped;
            return total > 0 ? (double) totalDropped / total : 0.0;
        }
    }
}
