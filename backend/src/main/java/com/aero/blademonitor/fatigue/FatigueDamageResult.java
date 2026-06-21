package com.aero.blademonitor.fatigue;

import lombok.Data;

@Data
public class FatigueDamageResult {
    private double totalDamage;
    private double maxAmplitude;
    private double avgAmplitude;
    private int cycleCount;
    private long timestamp;
    private double rpm;
    private int bladeIndex;
    private double damageRatio;
    private String severity;
    private boolean warning;
    private boolean critical;

    public static FatigueDamageResult of(double damage, int cycles, double maxAmp, double avgAmp) {
        FatigueDamageResult r = new FatigueDamageResult();
        r.setTotalDamage(damage);
        r.setCycleCount(cycles);
        r.setMaxAmplitude(maxAmp);
        r.setAvgAmplitude(avgAmp);
        r.setTimestamp(System.currentTimeMillis());
        return r;
    }
}
