package com.aero.blademonitor.model;

import lombok.Data;
import java.time.Instant;

@Data
public class BladeDataFrame {
    private long frameId;
    private long timestamp;
    private Instant receiveTime;
    private double rpm;
    private int bladeIndex;
    private int bladeCount;
    private double strain;
    private double temperature;
    private double rawAdcValue;
    private int packetSequence;
    private boolean isValid;
    private String validationNote;
}
