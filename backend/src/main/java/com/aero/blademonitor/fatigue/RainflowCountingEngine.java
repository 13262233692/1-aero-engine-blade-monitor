package com.aero.blademonitor.fatigue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

@Slf4j
@Component
public class RainflowCountingEngine {

    private final Deque<Double> stressHistory = new LinkedList<>();
    private final Deque<Long> timestampHistory = new LinkedList<>();
    private final Deque<Double> rpmHistory = new LinkedList<>();

    private final List<RainflowCycle> cycles = new ArrayList<>();

    private static final int MAX_HISTORY_SIZE = 10000;
    private static final double MIN_CYCLE_AMPLITUDE = 0.5;

    private double lastValue = Double.NaN;
    private int direction = 0;
    private double peakValue = Double.NaN;
    private double valleyValue = Double.NaN;
    private long totalReversals = 0;

    public synchronized void addDataPoint(double strain, long timestamp, double rpm, int bladeIndex) {
        if (Double.isNaN(strain)) return;

        if (Double.isNaN(lastValue)) {
            lastValue = strain;
            peakValue = strain;
            valleyValue = strain;
            return;
        }

        double delta = strain - lastValue;
        int newDirection = delta > 0 ? 1 : (delta < 0 ? -1 : 0);

        if (newDirection == 0) {
            lastValue = strain;
            return;
        }

        if (direction == 0) {
            direction = newDirection;
            lastValue = strain;
            if (direction > 0) {
                valleyValue = strain;
            } else {
                peakValue = strain;
            }
            return;
        }

        if (newDirection != direction) {
            if (direction > 0) {
                addReversalPoint(peakValue, timestamp, rpm);
                valleyValue = strain;
            } else {
                addReversalPoint(valleyValue, timestamp, rpm);
                peakValue = strain;
            }
            direction = newDirection;
        } else {
            if (direction > 0) {
                peakValue = Math.max(peakValue, strain);
            } else {
                valleyValue = Math.min(valleyValue, strain);
            }
        }

        lastValue = strain;
    }

    private void addReversalPoint(double value, long timestamp, double rpm) {
        stressHistory.addLast(value);
        timestampHistory.addLast(timestamp);
        rpmHistory.addLast(rpm);
        totalReversals++;

        if (stressHistory.size() > MAX_HISTORY_SIZE) {
            stressHistory.removeFirst();
            timestampHistory.removeFirst();
            rpmHistory.removeFirst();
        }

        processRainflowCounting();
    }

    private void processRainflowCounting() {
        while (stressHistory.size() >= 4) {
            List<Double> points = new ArrayList<>(stressHistory);
            double x1 = points.get(0);
            double x2 = points.get(1);
            double x3 = points.get(2);
            double x4 = points.get(3);

            double s12 = Math.abs(x2 - x1);
            double s23 = Math.abs(x3 - x2);
            double s34 = Math.abs(x4 - x3);

            if (s23 <= s12 && s23 <= s34) {
                double amplitude = s23 / 2.0;
                double mean = (x2 + x3) / 2.0;

                if (amplitude >= MIN_CYCLE_AMPLITUDE) {
                    RainflowCycle cycle = new RainflowCycle(amplitude, mean, 1.0);
                    cycle.setTimestamp(timestampHistory.peekFirst());
                    if (rpmHistory.size() >= 2) {
                        List<Double> rpms = new ArrayList<>(rpmHistory);
                        cycle.setRpm((rpms.get(1) + rpms.get(2)) / 2.0);
                    }
                    cycles.add(cycle);
                }

                stressHistory.removeFirst();
                stressHistory.removeFirst();
                timestampHistory.removeFirst();
                timestampHistory.removeFirst();
                rpmHistory.removeFirst();
                rpmHistory.removeFirst();
            } else {
                break;
            }
        }
    }

    public synchronized List<RainflowCycle> getCycles() {
        return new ArrayList<>(cycles);
    }

    public synchronized int getCycleCount() {
        return cycles.size();
    }

    public synchronized long getTotalReversals() {
        return totalReversals;
    }

    public synchronized void drainCycles() {
        cycles.clear();
    }

    public synchronized List<RainflowCycle> getAndDrainCycles() {
        List<RainflowCycle> result = new ArrayList<>(cycles);
        cycles.clear();
        return result;
    }

    public synchronized void reset() {
        stressHistory.clear();
        timestampHistory.clear();
        rpmHistory.clear();
        cycles.clear();
        lastValue = Double.NaN;
        direction = 0;
        peakValue = Double.NaN;
        valleyValue = Double.NaN;
        totalReversals = 0;
    }

    public synchronized double getMaxAmplitude() {
        double max = 0;
        for (RainflowCycle c : cycles) {
            if (c.getStrainAmplitude() > max) {
                max = c.getStrainAmplitude();
            }
        }
        return max;
    }

    public synchronized double getAverageAmplitude() {
        if (cycles.isEmpty()) return 0;
        double sum = 0;
        for (RainflowCycle c : cycles) {
            sum += c.getStrainAmplitude() * c.getCount();
        }
        return sum / cycles.size();
    }
}
