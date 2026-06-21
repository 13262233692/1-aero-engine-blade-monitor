package com.aero.blademonitor.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CampbellPoint {
    private double rpm;
    private double frequency;
    private double amplitude;
    private int order;
    private int bladeIndex;
    private long timestamp;
}
