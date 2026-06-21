package com.aero.blademonitor.fatigue;

import lombok.Data;

@Data
public class RainflowCycle {
    private double stressAmplitude;
    private double meanStress;
    private double strainAmplitude;
    private double meanStrain;
    private double count;
    private long timestamp;
    private double rpm;
    private int bladeIndex;

    public RainflowCycle(double strainAmplitude, double meanStrain, double count) {
        this.strainAmplitude = strainAmplitude;
        this.meanStrain = meanStrain;
        this.count = count;
    }
}
