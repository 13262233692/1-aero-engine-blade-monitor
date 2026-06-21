package com.aero.blademonitor.buffer;

public enum BackpressureLevel {
    NORMAL(0.0, 0, "正常"),
    LOW(0.30, 0, "低负载"),
    MEDIUM(0.60, 2, "中负载 - 采样丢弃"),
    HIGH(0.90, 4, "高负载 - 非关键帧丢弃"),
    CRITICAL(0.99, 10, "过载 - 主动丢弃");

    private final double threshold;
    private final int dropModulo;
    private final String description;

    BackpressureLevel(double threshold, int dropModulo, String description) {
        this.threshold = threshold;
        this.dropModulo = dropModulo;
        this.description = description;
    }

    public double getThreshold() { return threshold; }
    public int getDropModulo() { return dropModulo; }
    public String getDescription() { return description; }

    public boolean shouldDrop(long sequence) {
        if (dropModulo == 0) return false;
        return (sequence % dropModulo) == 0;
    }

    public static BackpressureLevel fromFillRatio(double ratio) {
        if (ratio >= CRITICAL.threshold) return CRITICAL;
        if (ratio >= HIGH.threshold) return HIGH;
        if (ratio >= MEDIUM.threshold) return MEDIUM;
        if (ratio >= LOW.threshold) return LOW;
        return NORMAL;
    }
}
