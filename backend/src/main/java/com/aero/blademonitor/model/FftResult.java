package com.aero.blademonitor.model;

import lombok.Data;
import java.util.List;

@Data
public class FftResult {
    private long timestamp;
    private double rpm;
    private int bladeIndex;
    private double[] frequencies;
    private double[] magnitudes;
    private double sampleRate;
    private int windowSize;
    private double fundamentalFrequency;
    private double firstOrderAmplitude;
    private List<Double> harmonicFrequencies;
    private List<Double> harmonicAmplitudes;
}
