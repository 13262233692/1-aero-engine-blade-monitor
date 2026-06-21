package com.aero.blademonitor.fatigue;

import com.aero.blademonitor.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Slf4j
@Component
public class MinerDamageCalculator {

    private final double fatigueLimitStrain;
    private final double cyclesToFailure;
    private final double snExponent;
    private final double damageWarningThreshold;
    private final double damageCriticalThreshold;
    private final double maxAmplitudeWarning;
    private final double maxAmplitudeCritical;

    private final Map<Integer, Double> cumulativeDamagePerBlade = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> totalCyclesPerBlade = new ConcurrentHashMap<>();
    private final Map<Integer, Double> maxAmplitudePerBlade = new ConcurrentHashMap<>();

    public MinerDamageCalculator(AppProperties appProperties) {
        AppProperties.FatigueProperties props = appProperties.getFatigue();
        this.fatigueLimitStrain = props.getFatigueLimitStrain();
        this.cyclesToFailure = props.getCyclesToFailure();
        this.snExponent = props.getSnExponent();
        this.damageWarningThreshold = props.getDamageWarningThreshold();
        this.damageCriticalThreshold = props.getDamageCriticalThreshold();
        this.maxAmplitudeWarning = props.getMaxAmplitudeWarning();
        this.maxAmplitudeCritical = props.getMaxAmplitudeCritical();
    }

    public FatigueDamageResult calculateDamage(List<RainflowCycle> cycles, int bladeIndex) {
        if (cycles == null || cycles.isEmpty()) {
            return FatigueDamageResult.of(0, 0, 0, 0);
        }

        double totalDamage = 0.0;
        double maxAmplitude = 0.0;
        double sumAmplitude = 0.0;
        int totalCount = 0;

        for (RainflowCycle cycle : cycles) {
            double amplitude = cycle.getStrainAmplitude();
            double count = cycle.getCount();

            if (amplitude > maxAmplitude) {
                maxAmplitude = amplitude;
            }
            sumAmplitude += amplitude * count;
            totalCount += count;

            double cyclesToFailure = calculateCyclesToFailure(amplitude);
            if (cyclesToFailure > 0) {
                totalDamage += count / cyclesToFailure;
            }
        }

        double prevDamage = cumulativeDamagePerBlade.getOrDefault(bladeIndex, 0.0);
        double newDamage = prevDamage + totalDamage;
        cumulativeDamagePerBlade.put(bladeIndex, newDamage);

        int prevCycles = totalCyclesPerBlade.getOrDefault(bladeIndex, 0);
        totalCyclesPerBlade.put(bladeIndex, prevCycles + totalCount);

        double prevMax = maxAmplitudePerBlade.getOrDefault(bladeIndex, 0.0);
        maxAmplitudePerBlade.put(bladeIndex, Math.max(prevMax, maxAmplitude));

        FatigueDamageResult result = FatigueDamageResult.of(
                newDamage,
                prevCycles + totalCount,
                Math.max(prevMax, maxAmplitude),
                totalCount > 0 ? sumAmplitude / totalCount : 0
        );

        result.setBladeIndex(bladeIndex);
        result.setDamageRatio(newDamage);

        boolean isWarning = newDamage >= damageWarningThreshold || maxAmplitude >= maxAmplitudeWarning;
        boolean isCritical = newDamage >= damageCriticalThreshold || maxAmplitude >= maxAmplitudeCritical;

        result.setWarning(isWarning);
        result.setCritical(isCritical);

        if (isCritical) {
            result.setSeverity("CRITICAL");
        } else if (isWarning) {
            result.setSeverity("WARNING");
        } else {
            result.setSeverity("NORMAL");
        }

        return result;
    }

    private double calculateCyclesToFailure(double strainAmplitude) {
        if (strainAmplitude <= 0) {
            return Double.POSITIVE_INFINITY;
        }

        if (strainAmplitude <= fatigueLimitStrain) {
            return cyclesToFailure;
        }

        double ratio = strainAmplitude / fatigueLimitStrain;
        return cyclesToFailure / Math.pow(ratio, snExponent);
    }

    public double getCumulativeDamage(int bladeIndex) {
        return cumulativeDamagePerBlade.getOrDefault(bladeIndex, 0.0);
    }

    public int getTotalCycles(int bladeIndex) {
        return totalCyclesPerBlade.getOrDefault(bladeIndex, 0);
    }

    public double getMaxAmplitude(int bladeIndex) {
        return maxAmplitudePerBlade.getOrDefault(bladeIndex, 0.0);
    }

    public void resetBlade(int bladeIndex) {
        cumulativeDamagePerBlade.remove(bladeIndex);
        totalCyclesPerBlade.remove(bladeIndex);
        maxAmplitudePerBlade.remove(bladeIndex);
    }

    public void resetAll() {
        cumulativeDamagePerBlade.clear();
        totalCyclesPerBlade.clear();
        maxAmplitudePerBlade.clear();
    }

    public double getDamageWarningThreshold() {
        return damageWarningThreshold;
    }

    public double getDamageCriticalThreshold() {
        return damageCriticalThreshold;
    }

    public double getMaxAmplitudeWarning() {
        return maxAmplitudeWarning;
    }

    public double getMaxAmplitudeCritical() {
        return maxAmplitudeCritical;
    }
}
