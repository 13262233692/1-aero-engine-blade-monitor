package com.aero.blademonitor.signal;

import com.aero.blademonitor.config.AppProperties;
import com.aero.blademonitor.model.FftResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class FftSignalProcessor {

    private final AppProperties appProperties;
    private final FastFourierTransformer fftTransformer;

    private final ConcurrentHashMap<Integer, ConcurrentLinkedDeque<Double>> strainBuffers;
    private final ConcurrentHashMap<Integer, Double> lastRpmMap;
    private final AtomicLong fftComputedCount;

    private final int windowSize;
    private final int hopSize;
    private final double sampleRate;
    private final double lowCutFreq;
    private final double highCutFreq;
    private final double[] windowCoefficients;

    public FftSignalProcessor(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.fftTransformer = new FastFourierTransformer(DftNormalization.STANDARD);

        this.strainBuffers = new ConcurrentHashMap<>();
        this.lastRpmMap = new ConcurrentHashMap<>();
        this.fftComputedCount = new AtomicLong(0);

        this.windowSize = appProperties.getFft().getWindowSize();
        this.hopSize = (int) (windowSize * (1.0 - appProperties.getFft().getOverlap()));
        this.sampleRate = appProperties.getFft().getSampleRate();
        this.lowCutFreq = appProperties.getFft().getLowCutFreq();
        this.highCutFreq = appProperties.getFft().getHighCutFreq();
        this.windowCoefficients = createHannWindow(windowSize);
    }

    private double[] createHannWindow(int size) {
        double[] window = new double[size];
        for (int i = 0; i < size; i++) {
            window[i] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (size - 1)));
        }
        return window;
    }

    public void addStrainSample(int bladeIndex, double strain, double rpm, long timestamp) {
        ConcurrentLinkedDeque<Double> buffer = strainBuffers.computeIfAbsent(
                bladeIndex, k -> new ConcurrentLinkedDeque<>()
        );

        buffer.addLast(strain);
        while (buffer.size() > windowSize * 2) {
            buffer.pollFirst();
        }

        lastRpmMap.put(bladeIndex, rpm);
    }

    public List<FftResult> processAvailableWindows() {
        List<FftResult> results = new ArrayList<>();

        for (var entry : strainBuffers.entrySet()) {
            int bladeIndex = entry.getKey();
            ConcurrentLinkedDeque<Double> buffer = entry.getValue();

            while (buffer.size() >= windowSize) {
                Double[] temp = buffer.toArray(new Double[0]);
                double[] samples = new double[windowSize];
                for (int i = 0; i < windowSize; i++) {
                    samples[i] = temp[i] != null ? temp[i] : 0.0;
                }

                removeFirst(buffer, hopSize);

                FftResult result = computeFft(bladeIndex, samples);
                if (result != null) {
                    Double rpm = lastRpmMap.getOrDefault(bladeIndex, 0.0);
                    result.setRpm(rpm);
                    result.setTimestamp(System.nanoTime());
                    results.add(result);
                    fftComputedCount.incrementAndGet();
                }
            }
        }

        return results;
    }

    private void removeFirst(ConcurrentLinkedDeque<Double> deque, int count) {
        for (int i = 0; i < count && !deque.isEmpty(); i++) {
            deque.pollFirst();
        }
    }

    public FftResult computeFft(int bladeIndex, double[] samples) {
        try {
            double[] windowed = applyWindow(samples);
            double[] padded = zeroPadToPowerOfTwo(windowed);
            Complex[] fftOutput = fftTransformer.transform(padded, TransformType.FORWARD);

            int n = fftOutput.length;
            int numBins = n / 2 + 1;
            double[] frequencies = new double[numBins];
            double[] magnitudes = new double[numBins];

            for (int i = 0; i < numBins; i++) {
                frequencies[i] = (i * sampleRate) / n;
                double re = fftOutput[i].getReal();
                double im = fftOutput[i].getImaginary();
                magnitudes[i] = Math.sqrt(re * re + im * im) / (windowSize / 2.0);
            }

            applyBandpassFilter(frequencies, magnitudes);

            int peakIndex = findDominantPeak(frequencies, magnitudes, lowCutFreq, highCutFreq);
            double fundamental = frequencies[peakIndex];
            double firstOrderAmp = magnitudes[peakIndex];

            List<Double> harmonicFreqs = new ArrayList<>();
            List<Double> harmonicAmps = new ArrayList<>();
            for (int h = 2; h <= 8; h++) {
                double targetFreq = fundamental * h;
                int idx = findNearestFrequency(frequencies, targetFreq);
                if (idx >= 0 && idx < frequencies.length) {
                    harmonicFreqs.add(frequencies[idx]);
                    harmonicAmps.add(magnitudes[idx]);
                }
            }

            FftResult result = new FftResult();
            result.setBladeIndex(bladeIndex);
            result.setFrequencies(frequencies);
            result.setMagnitudes(magnitudes);
            result.setSampleRate(sampleRate);
            result.setWindowSize(windowSize);
            result.setFundamentalFrequency(fundamental);
            result.setFirstOrderAmplitude(firstOrderAmp);
            result.setHarmonicFrequencies(harmonicFreqs);
            result.setHarmonicAmplitudes(harmonicAmps);

            return result;

        } catch (Exception e) {
            log.warn("FFT computation failed for blade {}: {}", bladeIndex, e.getMessage());
            return null;
        }
    }

    private double[] applyWindow(double[] samples) {
        double[] windowed = new double[samples.length];
        for (int i = 0; i < samples.length; i++) {
            windowed[i] = samples[i] * windowCoefficients[i];
        }
        return windowed;
    }

    private double[] zeroPadToPowerOfTwo(double[] samples) {
        int length = samples.length;
        int paddedLength = 1;
        while (paddedLength < length) {
            paddedLength <<= 1;
        }
        if (paddedLength == length) {
            return samples;
        }
        double[] padded = new double[paddedLength];
        System.arraycopy(samples, 0, padded, 0, length);
        return padded;
    }

    private void applyBandpassFilter(double[] frequencies, double[] magnitudes) {
        for (int i = 0; i < frequencies.length; i++) {
            if (frequencies[i] < lowCutFreq || frequencies[i] > highCutFreq) {
                magnitudes[i] *= 0.01;
            }
        }
    }

    private int findDominantPeak(double[] frequencies, double[] magnitudes, double fLow, double fHigh) {
        int peakIndex = 0;
        double peakMag = -1;

        for (int i = 1; i < frequencies.length - 1; i++) {
            if (frequencies[i] < fLow) continue;
            if (frequencies[i] > fHigh) break;

            if (magnitudes[i] > magnitudes[i - 1]
                    && magnitudes[i] >= magnitudes[i + 1]
                    && magnitudes[i] > peakMag) {
                boolean isLocalPeak = true;
                for (int j = Math.max(1, i - 5); j <= Math.min(frequencies.length - 2, i + 5); j++) {
                    if (j != i && magnitudes[j] > magnitudes[i]) {
                        isLocalPeak = false;
                        break;
                    }
                }
                if (isLocalPeak) {
                    peakIndex = i;
                    peakMag = magnitudes[i];
                }
            }
        }

        if (peakMag < 0) {
            for (int i = 1; i < frequencies.length - 1; i++) {
                if (frequencies[i] >= fLow && frequencies[i] <= fHigh) {
                    if (magnitudes[i] > peakMag) {
                        peakIndex = i;
                        peakMag = magnitudes[i];
                    }
                }
            }
        }

        if (peakIndex > 0 && peakIndex < frequencies.length - 1 && peakMag > 0) {
            double y1 = magnitudes[peakIndex - 1];
            double y2 = magnitudes[peakIndex];
            double y3 = magnitudes[peakIndex + 1];
            double denom = (y1 - 2 * y2 + y3);
            if (Math.abs(denom) > 1e-10) {
                double delta = 0.5 * (y1 - y3) / denom;
                double freqCorrection = delta * sampleRate / frequencies.length;
                double interpFreq = frequencies[peakIndex] + freqCorrection;
                frequencies[peakIndex] = interpFreq;
            }
        }

        return peakIndex;
    }

    private int findNearestFrequency(double[] frequencies, double target) {
        int idx = 0;
        double minDiff = Double.MAX_VALUE;
        for (int i = 0; i < frequencies.length; i++) {
            double diff = Math.abs(frequencies[i] - target);
            if (diff < minDiff) {
                minDiff = diff;
                idx = i;
            }
        }
        return idx;
    }

    public long getFftComputedCount() {
        return fftComputedCount.get();
    }

    public int getWindowSize() { return windowSize; }
    public double getSampleRate() { return sampleRate; }
    public double getLowCutFreq() { return lowCutFreq; }
    public double getHighCutFreq() { return highCutFreq; }
}
