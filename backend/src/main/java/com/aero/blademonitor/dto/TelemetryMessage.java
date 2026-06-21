package com.aero.blademonitor.dto;

import com.aero.blademonitor.model.CampbellPoint;
import lombok.Data;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TelemetryMessage {
    private String type;
    private long timestamp;
    private Double rpm;
    private Integer bladeIndex;
    private Integer bladeCount;
    private Double strain;
    private Double temperature;
    private Double frequency;
    private Double amplitude;
    private Integer order;
    private List<CampbellPoint> campbellPoints;
    private double[] spectrumFrequencies;
    private double[] spectrumMagnitudes;
    private Double fundamentalFrequency;
    private Double firstOrderAmplitude;
    private MetricsDto metrics;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class MetricsDto {
        private long packetsReceived;
        private long packetsDropped;
        private long framesParsed;
        private long fftComputed;
        private double packetsPerSecond;
        private double avgProcessingLatencyMs;
        private int bufferSize;
        private int wsClientCount;
    }
}
