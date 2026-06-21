package com.aero.blademonitor.fatigue;

import com.aero.blademonitor.config.AppProperties;
import com.aero.blademonitor.model.BladeDataFrame;
import com.aero.blademonitor.websocket.WebSocketBroadcaster;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class FatigueWarningService {

    private final WebSocketBroadcaster broadcaster;
    private final MinerDamageCalculator damageCalculator;
    private final AppProperties.FatigueProperties props;

    private final Map<Integer, RainflowCountingEngine> rainflowEngines = new ConcurrentHashMap<>();
    private final Map<Integer, Deque<Double>> strainHistoryMap = new ConcurrentHashMap<>();
    private final Map<Integer, Deque<Long>> timeHistoryMap = new ConcurrentHashMap<>();

    private final AtomicBoolean criticalWarningActive = new AtomicBoolean(false);
    private final AtomicLong lastCriticalWarningTime = new AtomicLong(0);
    private final AtomicLong totalCyclesProcessed = new AtomicLong(0);

    private ScheduledExecutorService scheduler;

    private static final int MAX_HISTORY_POINTS = 2000;

    public FatigueWarningService(WebSocketBroadcaster broadcaster,
                                  MinerDamageCalculator damageCalculator,
                                  AppProperties appProperties) {
        this.broadcaster = broadcaster;
        this.damageCalculator = damageCalculator;
        this.props = appProperties.getFatigue();
    }

    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fatigue-warning-monitor");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::checkFatigueStatus, 100, 100, TimeUnit.MILLISECONDS);

        log.info("Fatigue warning service initialized with damage threshold: {}",
                props.getDamageCriticalThreshold());
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    public void processFrame(BladeDataFrame frame) {
        if (frame == null || !frame.isValid()) return;

        int bladeIndex = frame.getBladeIndex();
        double strain = frame.getStrain();
        long timestamp = frame.getTimestamp();
        double rpm = frame.getRpm();

        RainflowCountingEngine engine = rainflowEngines.computeIfAbsent(
                bladeIndex, k -> new RainflowCountingEngine()
        );

        engine.addDataPoint(strain, timestamp, rpm, bladeIndex);

        Deque<Double> strainHistory = strainHistoryMap.computeIfAbsent(
                bladeIndex, k -> new LinkedList<>()
        );
        Deque<Long> timeHistory = timeHistoryMap.computeIfAbsent(
                bladeIndex, k -> new LinkedList<>()
        );

        strainHistory.addLast(strain);
        timeHistory.addLast(timestamp);

        while (strainHistory.size() > MAX_HISTORY_POINTS) {
            strainHistory.removeFirst();
            timeHistory.removeFirst();
        }

        if (engine.getCycleCount() >= props.getMinCycleBatchSize()) {
            processBatchCycles(engine, bladeIndex);
        }
    }

    private void processBatchCycles(RainflowCountingEngine engine, int bladeIndex) {
        List<RainflowCycle> cycles = engine.getAndDrainCycles();
        if (cycles.isEmpty()) return;

        totalCyclesProcessed.addAndGet(cycles.size());

        FatigueDamageResult result = damageCalculator.calculateDamage(cycles, bladeIndex);

        if (result.isCritical()) {
            triggerCriticalWarning(result);
        }
    }

    private void checkFatigueStatus() {
        try {
            FatigueDamageResult worstResult = findWorstDamage();

            if (worstResult != null && worstResult.isCritical()) {
                triggerCriticalWarning(worstResult);
            }

            broadcastFatigueMetrics(worstResult);

        } catch (Exception e) {
            log.error("Error checking fatigue status", e);
        }
    }

    private FatigueDamageResult findWorstDamage() {
        FatigueDamageResult worst = null;
        double maxDamage = 0;

        for (Map.Entry<Integer, RainflowCountingEngine> entry : rainflowEngines.entrySet()) {
            int bladeIndex = entry.getKey();
            double damage = damageCalculator.getCumulativeDamage(bladeIndex);
            double maxAmp = damageCalculator.getMaxAmplitude(bladeIndex);
            int cycles = damageCalculator.getTotalCycles(bladeIndex);

            if (damage > maxDamage) {
                maxDamage = damage;
                worst = FatigueDamageResult.of(damage, cycles, maxAmp, 0);
                worst.setBladeIndex(bladeIndex);
                worst.setDamageRatio(damage);

                boolean isWarning = damage >= damageCalculator.getDamageWarningThreshold()
                        || maxAmp >= damageCalculator.getMaxAmplitudeWarning();
                boolean isCritical = damage >= damageCalculator.getDamageCriticalThreshold()
                        || maxAmp >= damageCalculator.getMaxAmplitudeCritical();

                worst.setWarning(isWarning);
                worst.setCritical(isCritical);
                worst.setSeverity(isCritical ? "CRITICAL" : (isWarning ? "WARNING" : "NORMAL"));
            }
        }

        return worst;
    }

    private void triggerCriticalWarning(FatigueDamageResult result) {
        long now = System.currentTimeMillis();

        if (criticalWarningActive.get()
                && (now - lastCriticalWarningTime.get()) < props.getWarningCooldownMs()) {
            return;
        }

        criticalWarningActive.set(true);
        lastCriticalWarningTime.set(now);

        log.warn(String.format("FATIGUE CRITICAL WARNING - Blade #%d - Damage: %.4f (%.2f%%) - Max Amp: %.1f με - Cycles: %d",
                result.getBladeIndex(),
                result.getDamageRatio(),
                result.getDamageRatio() * 100,
                result.getMaxAmplitude(),
                result.getCycleCount()));

        broadcastWarning(result);
    }

    private void broadcastWarning(FatigueDamageResult result) {
        if (!broadcaster.hasClients()) return;

        int bladeIndex = result.getBladeIndex();
        List<Double> strainHistory = new ArrayList<>(
                strainHistoryMap.getOrDefault(bladeIndex, new LinkedList<>())
        );
        List<Long> timeHistory = new ArrayList<>(
                timeHistoryMap.getOrDefault(bladeIndex, new LinkedList<>())
        );

        int displayPoints = Math.min(500, strainHistory.size());
        List<Double> displayStrain = new ArrayList<>();
        List<Long> displayTime = new ArrayList<>();

        int step = Math.max(1, strainHistory.size() / displayPoints);
        for (int i = 0; i < strainHistory.size(); i += step) {
            displayStrain.add(strainHistory.get(i));
            if (i < timeHistory.size()) {
                displayTime.add(timeHistory.get(i));
            }
        }

        String msgJson = String.format(
                "{\"type\":\"fatigue_warning\"," +
                        "\"severity\":\"%s\"," +
                        "\"bladeIndex\":%d," +
                        "\"damageRatio\":%.4f," +
                        "\"maxAmplitude\":%.2f," +
                        "\"avgAmplitude\":%.2f," +
                        "\"cycleCount\":%d," +
                        "\"rpm\":%.1f," +
                        "\"timestamp\":%d," +
                        "\"damageWarningThreshold\":%.2f," +
                        "\"damageCriticalThreshold\":%.2f," +
                        "\"strainHistory\":[%s]," +
                        "\"timeHistory\":[%s]}",
                result.getSeverity(),
                result.getBladeIndex(),
                result.getDamageRatio(),
                result.getMaxAmplitude(),
                result.getAvgAmplitude(),
                result.getCycleCount(),
                result.getRpm(),
                result.getTimestamp(),
                damageCalculator.getDamageWarningThreshold(),
                damageCalculator.getDamageCriticalThreshold(),
                joinDoubles(displayStrain),
                joinLongs(displayTime)
        );

        broadcaster.broadcastRawJson(msgJson);
    }

    private void broadcastFatigueMetrics(FatigueDamageResult worstResult) {
        if (!broadcaster.hasClients()) return;
        if (worstResult == null) return;

        String msgJson = String.format(
                "{\"type\":\"fatigue_metrics\"," +
                        "\"bladeIndex\":%d," +
                        "\"damageRatio\":%.4f," +
                        "\"maxAmplitude\":%.2f," +
                        "\"totalCycles\":%d," +
                        "\"severity\":\"%s\"," +
                        "\"totalCyclesProcessed\":%d," +
                        "\"timestamp\":%d}",
                worstResult.getBladeIndex(),
                worstResult.getDamageRatio(),
                worstResult.getMaxAmplitude(),
                worstResult.getCycleCount(),
                worstResult.getSeverity(),
                totalCyclesProcessed.get(),
                System.currentTimeMillis()
        );

        broadcaster.broadcastRawJson(msgJson);
    }

    private String joinDoubles(List<Double> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format("%.2f", list.get(i)));
        }
        return sb.toString();
    }

    private String joinLongs(List<Long> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    public void acknowledgeWarning() {
        criticalWarningActive.set(false);
        log.info("Fatigue warning acknowledged by operator");
    }

    public void resetAll() {
        rainflowEngines.clear();
        strainHistoryMap.clear();
        timeHistoryMap.clear();
        damageCalculator.resetAll();
        criticalWarningActive.set(false);
        totalCyclesProcessed.set(0);
        log.info("All fatigue data reset");
    }

    public boolean isCriticalWarningActive() {
        return criticalWarningActive.get();
    }

    public long getTotalCyclesProcessed() {
        return totalCyclesProcessed.get();
    }
}
