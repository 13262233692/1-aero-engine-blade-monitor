package com.aero.blademonitor.pipeline;

import com.aero.blademonitor.buffer.HighSpeedBufferManager;
import com.aero.blademonitor.model.BladeDataFrame;
import com.aero.blademonitor.model.CampbellPoint;
import com.aero.blademonitor.model.FftResult;
import com.aero.blademonitor.model.RawPacket;
import com.aero.blademonitor.parser.HexStreamParser;
import com.aero.blademonitor.signal.FftSignalProcessor;
import com.aero.blademonitor.websocket.WebSocketBroadcaster;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class SignalProcessingPipeline {

    private final HighSpeedBufferManager bufferManager;
    private final HexStreamParser parser;
    private final FftSignalProcessor fftProcessor;
    private final WebSocketBroadcaster broadcaster;

    private final AtomicLong totalFrames = new AtomicLong(0);
    private final AtomicLong totalFftResults = new AtomicLong(0);
    private final AtomicLong lastLatencySampleTime = new AtomicLong(System.nanoTime());
    private final AtomicLong accumulatedLatencyNs = new AtomicLong(0);
    private final AtomicLong latencySampleCount = new AtomicLong(0);

    private final ConcurrentLinkedDeque<CampbellPoint> campbellPointBuffer = new ConcurrentLinkedDeque<>();
    private static final int MAX_CAMPBELL_BUFFER = 5000;

    public SignalProcessingPipeline(
            HighSpeedBufferManager bufferManager,
            HexStreamParser parser,
            FftSignalProcessor fftProcessor,
            WebSocketBroadcaster broadcaster) {
        this.bufferManager = bufferManager;
        this.parser = parser;
        this.fftProcessor = fftProcessor;
        this.broadcaster = broadcaster;
    }

    @PostConstruct
    public void init() {
        bufferManager.setPacketConsumer(this::processPacket);
        log.info("Signal processing pipeline initialized");
    }

    private void processPacket(RawPacket packet) {
        long startNs = System.nanoTime();

        try {
            List<BladeDataFrame> frames = parser.parse(packet.getPayload());

            for (BladeDataFrame frame : frames) {
                if (!frame.isValid()) continue;

                totalFrames.incrementAndGet();
                processFrame(frame);
            }

        } catch (Exception e) {
            log.error("Error processing packet", e);
        } finally {
            long latency = System.nanoTime() - startNs;
            accumulatedLatencyNs.addAndGet(latency);
            latencySampleCount.incrementAndGet();
        }
    }

    private void processFrame(BladeDataFrame frame) {
        broadcaster.broadcastFrame(frame);

        fftProcessor.addStrainSample(
                frame.getBladeIndex(),
                frame.getStrain(),
                frame.getRpm(),
                frame.getTimestamp()
        );
    }

    @Scheduled(fixedRate = 20, initialDelay = 500)
    public void processFftWindows() {
        try {
            List<FftResult> results = fftProcessor.processAvailableWindows();
            if (results.isEmpty()) return;

            for (FftResult result : results) {
                totalFftResults.incrementAndGet();
                processFftResult(result);
            }
        } catch (Exception e) {
            log.error("Error in FFT processing scheduled task", e);
        }
    }

    private void processFftResult(FftResult result) {
        broadcaster.broadcastFftResult(result);

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

        CampbellPoint firstOrder = new CampbellPoint(
                rpm,
                result.getFundamentalFrequency(),
                result.getFirstOrderAmplitude(),
                1,
                bladeIdx,
                ts
        );
        points.add(firstOrder);

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
        if (campbellPointBuffer.isEmpty()) return;

        List<CampbellPoint> batch = new ArrayList<>();
        CampbellPoint point;
        int maxPerBatch = 200;
        while ((point = campbellPointBuffer.pollFirst()) != null && batch.size() < maxPerBatch) {
            batch.add(point);
        }

        if (!batch.isEmpty()) {
            broadcaster.broadcastCampbellBatch(batch);
        }
    }

    @Scheduled(fixedRate = 1000, initialDelay = 2000)
    public void broadcastMetrics() {
        double avgLatencyMs = 0;
        long count = latencySampleCount.get();
        if (count > 0) {
            avgLatencyMs = (accumulatedLatencyNs.get() / (double) count) / 1_000_000.0;
        }

        broadcaster.broadcastMetrics(
                bufferManager.getPublishedCount(),
                bufferManager.getDroppedCount(),
                parser.getFramesParsed(),
                totalFftResults.get(),
                0,
                avgLatencyMs,
                bufferManager.getCurrentBufferSize(),
                broadcaster.getClientCount()
        );
    }

    public List<CampbellPoint> getCurrentCampbellPointsSnapshot() {
        return new ArrayList<>(campbellPointBuffer);
    }

    public long getTotalFrames() { return totalFrames.get(); }
    public long getTotalFftResults() { return totalFftResults.get(); }
}
