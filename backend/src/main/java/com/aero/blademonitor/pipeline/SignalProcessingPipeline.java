package com.aero.blademonitor.pipeline;

import com.aero.blademonitor.buffer.BackpressureLevel;
import com.aero.blademonitor.buffer.DisruptorBufferManager;
import com.aero.blademonitor.buffer.RawPacketPool;
import com.aero.blademonitor.dto.TelemetryMessage;
import com.aero.blademonitor.model.BladeDataFrame;
import com.aero.blademonitor.model.CampbellPoint;
import com.aero.blademonitor.model.FftResult;
import com.aero.blademonitor.model.RawPacket;
import com.aero.blademonitor.parser.HexStreamParser;
import com.aero.blademonitor.persistence.AsyncPersistenceService;
import com.aero.blademonitor.signal.FftSignalProcessor;
import com.aero.blademonitor.udp.NioUdpReceiver;
import com.aero.blademonitor.websocket.WebSocketBroadcaster;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

@Slf4j
@Service
public class SignalProcessingPipeline {

    private final DisruptorBufferManager bufferManager;
    private final HexStreamParser parser;
    private final FftSignalProcessor fftProcessor;
    private final WebSocketBroadcaster broadcaster;
    private final AsyncPersistenceService persistenceService;
    private final NioUdpReceiver udpReceiver;
    private final RawPacketPool packetPool;

    private final BlockingQueue<BladeDataFrame> parsedFramesForFft = new LinkedBlockingQueue<>();
    private final BlockingQueue<BladeDataFrame> parsedFramesForBroadcast = new LinkedBlockingQueue<>();
    private final BlockingQueue<BladeDataFrame> parsedFramesForPersistence = new LinkedBlockingQueue<>();

    private final ConcurrentLinkedDeque<CampbellPoint> campbellPointBuffer = new ConcurrentLinkedDeque<>();
    private static final int MAX_CAMPBELL_BUFFER = 10000;

    private final AtomicLong totalFrames = new AtomicLong(0);
    private final AtomicLong totalFftResults = new AtomicLong(0);
    private final AtomicLong parseLatencyNs = new AtomicLong(0);
    private final AtomicLong parseCount = new AtomicLong(0);
    private final AtomicLong fftLatencyNs = new AtomicLong(0);
    private final AtomicLong fftCount = new AtomicLong(0);
    private final AtomicLong broadcastLatencyNs = new AtomicLong(0);
    private final AtomicLong broadcastCount = new AtomicLong(0);

    public SignalProcessingPipeline(
            DisruptorBufferManager bufferManager,
            HexStreamParser parser,
            FftSignalProcessor fftProcessor,
            WebSocketBroadcaster broadcaster,
            AsyncPersistenceService persistenceService,
            NioUdpReceiver udpReceiver,
            RawPacketPool packetPool) {
        this.bufferManager = bufferManager;
        this.parser = parser;
        this.fftProcessor = fftProcessor;
        this.broadcaster = broadcaster;
        this.persistenceService = persistenceService;
        this.udpReceiver = udpReceiver;
        this.packetPool = packetPool;
    }

    @PostConstruct
    public void init() {
        bufferManager.setParserConsumer(this::handleParserBatch);
        bufferManager.setFftConsumer(this::handleFftBatch);
        bufferManager.setBroadcastConsumer(this::handleBroadcastBatch);
        bufferManager.setPersistenceConsumer(this::handlePersistenceBatch);

        log.info("Multi-stage signal processing pipeline initialized with thread-isolated consumers");
    }

    private void handleParserBatch(List<RawPacket> batch) {
        if (batch == null || batch.isEmpty()) return;

        long startNs = System.nanoTime();

        try {
            for (RawPacket packet : batch) {
                if (packet == null || packet.getPayload() == null) continue;

                List<BladeDataFrame> frames = parser.parse(packet.getPayload());

                for (BladeDataFrame frame : frames) {
                    if (!frame.isValid()) continue;

                    totalFrames.incrementAndGet();

                    if (!parsedFramesForFft.offer(frame)) {
                    }
                    if (!parsedFramesForBroadcast.offer(frame)) {
                    }
                    if (!parsedFramesForPersistence.offer(frame)) {
                    }
                }
            }
        } finally {
            long latency = System.nanoTime() - startNs;
            parseLatencyNs.addAndGet(latency);
            parseCount.addAndGet(batch.size());
        }
    }

    private void handleFftBatch(List<RawPacket> batch) {
        if (batch == null || batch.isEmpty()) return;
        if (parsedFramesForFft.isEmpty()) return;

        long startNs = System.nanoTime();
        int processed = 0;

        try {
            List<BladeDataFrame> drainList = new ArrayList<>();
            parsedFramesForFft.drainTo(drainList, 512);

            for (BladeDataFrame frame : drainList) {
                if (frame == null) continue;

                fftProcessor.addStrainSample(
                        frame.getBladeIndex(),
                        frame.getStrain(),
                        frame.getRpm(),
                        frame.getTimestamp()
                );
                processed++;
            }

            processFftWindows();

        } finally {
            long latency = System.nanoTime() - startNs;
            fftLatencyNs.addAndGet(latency);
            fftCount.addAndGet(Math.max(1, processed));
        }
    }

    private void handleBroadcastBatch(List<RawPacket> batch) {
        if (batch == null || batch.isEmpty()) return;
        if (!broadcaster.hasClients()) return;

        long startNs = System.nanoTime();
        int processed = 0;

        try {
            List<BladeDataFrame> drainList = new ArrayList<>();
            parsedFramesForBroadcast.drainTo(drainList, 256);

            for (BladeDataFrame frame : drainList) {
                if (frame == null) continue;
                broadcaster.broadcastFrame(frame);
                processed++;
            }
        } finally {
            long latency = System.nanoTime() - startNs;
            broadcastLatencyNs.addAndGet(latency);
            broadcastCount.addAndGet(Math.max(1, processed));
        }
    }

    private void handlePersistenceBatch(List<RawPacket> batch) {
        if (batch == null || batch.isEmpty()) return;
        if (!persistenceService.isPersistenceEnabled()) return;

        try {
            List<BladeDataFrame> drainList = new ArrayList<>();
            parsedFramesForPersistence.drainTo(drainList, 1024);

            if (!drainList.isEmpty()) {
                persistenceService.enqueueBatch(drainList);
            }
        } catch (Exception e) {
            log.warn("Persistence batch handling error", e);
        }
    }

    private void processFftWindows() {
        try {
            List<FftResult> results = fftProcessor.processAvailableWindows();
            if (results.isEmpty()) return;

            for (FftResult result : results) {
                totalFftResults.incrementAndGet();
                processFftResult(result);
            }
        } catch (Exception e) {
            log.error("Error in FFT processing", e);
        }
    }

    private void processFftResult(FftResult result) {
        try {
            broadcaster.broadcastFftResult(result);
        } catch (Exception e) {
            log.warn("FFT broadcast error", e);
        }

        List<CampbellPoint> points = generateCampbellPoints(result);
        for (CampbellPoint point : points) {
            campbellPointBuffer.addLast(point);
            while (campbellPointBuffer.size() > MAX_CAMPBELL_BUFFER) {
                campbellPointBuffer.pollFirst();
            }
        }
    }

    private List<CampbellPoint> generateCampbellPoints(FftResult result) {
        List<CampbellPoint> points = new ArrayList<>();
        double rpm = result.getRpm();
        long ts = result.getTimestamp();
        int bladeIdx = result.getBladeIndex();

        points.add(new CampbellPoint(
                rpm,
                result.getFundamentalFrequency(),
                result.getFirstOrderAmplitude(),
                1,
                bladeIdx,
                ts
        ));

        List<Double> harmonicFreqs = result.getHarmonicFrequencies();
        List<Double> harmonicAmps = result.getHarmonicAmplitudes();
        if (harmonicFreqs != null) {
            for (int i = 0; i < harmonicFreqs.size(); i++) {
                points.add(new CampbellPoint(
                        rpm,
                        harmonicFreqs.get(i),
                        harmonicAmps.get(i),
                        i + 2,
                        bladeIdx,
                        ts
                ));
            }
        }

        return points;
    }

    @Scheduled(fixedRate = 16, initialDelay = 1000)
    public void broadcastCampbellBatch() {
        if (!broadcaster.hasClients() || campbellPointBuffer.isEmpty()) return;

        List<CampbellPoint> batch = new ArrayList<>();
        CampbellPoint point;
        int maxPerBatch = 200;
        while ((point = campbellPointBuffer.pollFirst()) != null && batch.size() < maxPerBatch) {
            batch.add(point);
        }

        if (!batch.isEmpty()) {
            try {
                broadcaster.broadcastCampbellBatch(batch);
            } catch (Exception e) {
                log.warn("Campbell broadcast error", e);
            }
        }
    }

    @Scheduled(fixedRate = 1000, initialDelay = 2000)
    public void broadcastMetrics() {
        double avgParseLatencyMs = 0;
        if (parseCount.get() > 0) {
            avgParseLatencyMs = (parseLatencyNs.get() / (double) parseCount.get()) / 1_000_000.0;
        }

        double avgFftLatencyMs = 0;
        if (fftCount.get() > 0) {
            avgFftLatencyMs = (fftLatencyNs.get() / (double) fftCount.get()) / 1_000_000.0;
        }

        double avgBroadcastLatencyMs = 0;
        if (broadcastCount.get() > 0) {
            avgBroadcastLatencyMs = (broadcastLatencyNs.get() / (double) broadcastCount.get()) / 1_000_000.0;
        }

        double combinedLatencyMs = avgParseLatencyMs + avgFftLatencyMs + avgBroadcastLatencyMs;

        try {
            TelemetryMessage.MetricsDto metrics = TelemetryMessage.MetricsDto.builder()
                    .packetsReceived(bufferManager.getPublishedCount())
                    .packetsDropped(bufferManager.getTotalDropped())
                    .framesParsed(totalFrames.get())
                    .fftComputed(totalFftResults.get())
                    .packetsPerSecond(udpReceiver.getCurrentPacketsPerSecond())
                    .avgProcessingLatencyMs(combinedLatencyMs)
                    .bufferSize((int) bufferManager.getCurrentBacklog())
                    .wsClientCount(broadcaster.getClientCount())
                    .build();

            TelemetryMessage msg = TelemetryMessage.builder()
                    .type("metrics")
                    .timestamp(System.currentTimeMillis())
                    .metrics(metrics)
                    .build();

            broadcaster.broadcastJson(msg);

        } catch (Exception e) {
            log.warn("Metrics broadcast error", e);
        }

        logPeriodicStats(avgParseLatencyMs, avgFftLatencyMs, avgBroadcastLatencyMs, combinedLatencyMs);
    }

    private long lastStatsLogTime = 0;

    private void logPeriodicStats(double parseMs, double fftMs, double broadcastMs, double totalMs) {
        long now = System.currentTimeMillis();
        if (now - lastStatsLogTime < 5000) return;
        lastStatsLogTime = now;

        BackpressureLevel bp = bufferManager.getCurrentLevel();
        RawPacketPool.PoolStats poolStats = packetPool.getStats();
        AsyncPersistenceService.PersistenceStats persStats = persistenceService.getStats();

        log.info(String.format("PIPELINE | BP:%s | PPS:%.0f | " +
                        "Parse: %.2fms | FFT: %.2fms | BC: %.2fms | " +
                        "Frames: %,d | FFTs: %,d | " +
                        "Drop: %,d (BP) %,d (Cap) | " +
                        "Pool: %d/%d (%.1f%%) | " +
                        "Persist: %,d/%,d | " +
                        "Clients: %d",
                bp.name(),
                udpReceiver.getCurrentPacketsPerSecond(),
                parseMs, fftMs, broadcastMs,
                totalFrames.get(), totalFftResults.get(),
                bufferManager.getDroppedByBackpressure(),
                bufferManager.getDroppedByCapacity(),
                poolStats.getCurrentSize(), poolStats.getMaxSize(), poolStats.getHitRate() * 100,
                persStats.getTotalPersisted(), persStats.getTotalEnqueued(),
                broadcaster.getClientCount()
        ));

        if (bp.ordinal() >= BackpressureLevel.HIGH.ordinal()) {
            log.warn(String.format("HIGH BACKPRESSURE DETECTED - Level: %s, Backlog: %d, Fill: %.1f%%",
                    bp.name(),
                    bufferManager.getCurrentBacklog(),
                    bufferManager.getFillRatio() * 100));
        }
    }

    public List<CampbellPoint> getCurrentCampbellPointsSnapshot() {
        return new ArrayList<>(campbellPointBuffer);
    }

    public long getTotalFrames() { return totalFrames.get(); }
    public long getTotalFftResults() { return totalFftResults.get(); }
}
