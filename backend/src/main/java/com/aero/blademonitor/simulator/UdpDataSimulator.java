package com.aero.blademonitor.simulator;

import com.aero.blademonitor.config.AppProperties;
import com.aero.blademonitor.parser.HexStreamParser;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.simulator.enabled", havingValue = "true", matchIfMissing = false)
public class UdpDataSimulator {

    private static final int MAX_PAYLOADS_PER_PACKET = 64;

    private final AppProperties appProperties;

    private DatagramSocket socket;
    private Thread simulatorThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AtomicInteger sequence = new AtomicInteger(0);
    private final AtomicLong packetsSent = new AtomicLong(0);
    private final AtomicLong bytesSent = new AtomicLong(0);
    private long startTimeMs;

    private double currentRpm = 0;
    private double targetRpm = 8000;
    private long lastRampTime = 0;
    private long lastReportTime = 0;
    private long lastReportCount = 0;

    private final int bladeCount;
    private final int targetPps;
    private final boolean rpmRampMode;

    private final InetAddress targetAddress;
    private final int targetPort;

    private final byte[][] payloadBuffer;
    private final DatagramPacket[] datagramBuffer;
    private int bufferIndex = 0;

    @Autowired
    public UdpDataSimulator(AppProperties appProperties) throws Exception {
        this.appProperties = appProperties;
        this.bladeCount = appProperties.getData().getBladeCount();
        this.targetPps = appProperties.getSimulator().getTargetPps();
        this.rpmRampMode = appProperties.getSimulator().isRpmRampMode();
        this.targetAddress = InetAddress.getByName("127.0.0.1");
        this.targetPort = appProperties.getUdp().getPort();

        this.payloadBuffer = new byte[MAX_PAYLOADS_PER_PACKET][];
        this.datagramBuffer = new DatagramPacket[MAX_PAYLOADS_PER_PACKET];

        log.info("UDP Simulator configured. Target: {} pps, Blade count: {}, RPM ramp mode: {}",
                String.format("%,d", targetPps), bladeCount, rpmRampMode);
    }

    @PostConstruct
    public void start() {
        try {
            socket = new DatagramSocket();
            socket.setSendBufferSize(200 * 1024 * 1024);
            socket.setTrafficClass(0x10);

            running.set(true);
            simulatorThread = new Thread(this::runSimulation, "udp-simulator");
            simulatorThread.setDaemon(true);
            simulatorThread.setPriority(Thread.MAX_PRIORITY);
            simulatorThread.start();

            log.info("UDP Data Simulator started, sending to {}:{}", targetAddress, targetPort);
        } catch (SocketException e) {
            log.error("Failed to start UDP simulator", e);
        }
    }

    public void runSimulation() {
        startTimeMs = System.currentTimeMillis();
        lastRampTime = startTimeMs;
        lastReportTime = startTimeMs;

        long intervalNs = 1_000_000_000L / targetPps;
        long nextSendTime = System.nanoTime();

        int bladePhase = 0;
        double simTime = 0;
        long burstSize = Math.max(1, targetPps / 10000);
        long burstIntervalNs = intervalNs * burstSize;

        while (running.get() && !Thread.interrupted()) {
            long now = System.nanoTime();
            if (now < nextSendTime) {
                long sleepNs = nextSendTime - now;
                if (sleepNs > 100_000) {
                    LockSupport.parkNanos(sleepNs);
                    continue;
                } else if (sleepNs > 0) {
                    Thread.onSpinWait();
                    continue;
                }
            }

            if (rpmRampMode) {
                updateRpmRamp();
            }

            simTime += (burstSize * intervalNs) / 1_000_000_000.0;

            int packetsInBurst = 0;
            for (int i = 0; i < burstSize && packetsInBurst < MAX_PAYLOADS_PER_PACKET; i++) {
                int bladeIndex = bladePhase % bladeCount;

                double rotationalFreqHz = currentRpm / 60.0;
                double bladePassFreq = rotationalFreqHz * bladeCount;

                double strain = generateStrainSignal(
                        simTime + i * intervalNs / 1_000_000_000.0,
                        bladeIndex,
                        rotationalFreqHz,
                        bladePassFreq
                );

                double temperature = 80 + 40 * Math.sin(simTime * 0.1) + bladeIndex * 0.5;

                long timestampUs = (System.nanoTime() / 1000) & 0xFFFFFFFFFFFFL;

                byte[] frame = HexStreamParser.buildFrame(
                        sequence.incrementAndGet() & 0xFFFF,
                        currentRpm,
                        bladeIndex,
                        bladeCount,
                        strain,
                        temperature,
                        timestampUs
                );

                sendFrame(frame);
                bladePhase++;
                packetsInBurst++;
            }

            flushSendBuffer();

            nextSendTime += burstIntervalNs;

            if (packetsSent.get() % 500000 == 0) {
                reportThroughput();
            }
        }
    }

    private void updateRpmRamp() {
        long elapsed = System.currentTimeMillis() - lastRampTime;
        double rampRate = 100.0;

        if (elapsed > 10000) {
            if (targetRpm < 3000) {
                targetRpm = 6000;
            } else if (targetRpm < 8000) {
                targetRpm = 12000;
            } else if (targetRpm < 13000) {
                targetRpm = 15000;
            } else if (targetRpm < 17000) {
                targetRpm = 18000;
            } else if (targetRpm < 19500) {
                targetRpm = 2000;
                rampRate = 200.0;
            } else {
                targetRpm = 2000;
                rampRate = 200.0;
            }
            lastRampTime = System.currentTimeMillis();
        }

        if (currentRpm < targetRpm) {
            currentRpm = Math.min(currentRpm + rampRate / 1000.0, targetRpm);
        } else if (currentRpm > targetRpm) {
            currentRpm = Math.max(currentRpm - rampRate / 1000.0, targetRpm);
        }
    }

    private double generateStrainSignal(double time, int bladeIndex,
                                         double rotationalFreq, double bladePassFreq) {
        double phase = 2.0 * Math.PI * bladeIndex / bladeCount;
        double rpmSweepPhase = time * 0.05;

        double signal = 0;

        signal += 180.0 * Math.sin(2.0 * Math.PI * rotationalFreq * time + phase);

        signal += 90.0 * Math.sin(2.0 * Math.PI * 2 * rotationalFreq * time + phase * 2);
        signal += 50.0 * Math.sin(2.0 * Math.PI * 3 * rotationalFreq * time + phase * 3);
        signal += 30.0 * Math.sin(2.0 * Math.PI * 4 * rotationalFreq * time + phase * 4);
        signal += 15.0 * Math.sin(2.0 * Math.PI * 5 * rotationalFreq * time);

        signal += 80.0 * Math.sin(2.0 * Math.PI * bladePassFreq * time + phase);
        signal += 35.0 * Math.sin(2.0 * Math.PI * 2 * bladePassFreq * time);
        signal += 15.0 * Math.sin(2.0 * Math.PI * 3 * bladePassFreq * time);

        signal += 8.0 * Math.sin(2.0 * Math.PI * (100 + bladeIndex * 7) * time);
        signal += 5.0 * Math.sin(2.0 * Math.PI * (350 + bladeIndex * 3) * time);
        signal += 3.0 * Math.sin(2.0 * Math.PI * (720 + bladeIndex * 5) * time);

        signal += (Math.random() - 0.5) * 25.0;

        signal += 60.0 * Math.sin(2.0 * Math.PI * (400 + 60 * Math.sin(rpmSweepPhase)) * time);
        signal += 30.0 * Math.sin(2.0 * Math.PI * (850 + 40 * Math.sin(rpmSweepPhase * 0.7)) * time);

        return signal;
    }

    private void sendFrame(byte[] frame) {
        payloadBuffer[bufferIndex] = frame;
        datagramBuffer[bufferIndex] = new DatagramPacket(
                frame, frame.length, targetAddress, targetPort
        );
        bufferIndex++;

        if (bufferIndex >= MAX_PAYLOADS_PER_PACKET) {
            flushSendBuffer();
        }
    }

    private void flushSendBuffer() {
        if (bufferIndex == 0) return;

        try {
            for (int i = 0; i < bufferIndex; i++) {
                socket.send(datagramBuffer[i]);
                packetsSent.incrementAndGet();
                bytesSent.addAndGet(datagramBuffer[i].getLength());
            }
        } catch (IOException e) {
            log.debug("Simulator send error", e);
        } finally {
            bufferIndex = 0;
        }
    }

    private void reportThroughput() {
        long now = System.currentTimeMillis();
        long elapsedMs = now - lastReportTime;
        long count = packetsSent.get();
        long deltaCount = count - lastReportCount;

        if (elapsedMs > 0) {
            double pps = (deltaCount * 1000.0) / elapsedMs;
            double mbps = (bytesSent.getAndSet(0) * 8.0) / (elapsedMs * 1000.0);

            log.info(String.format("SIMULATOR | Target: %,d pps | Actual: %,.0f pps | %.2f Mbps | " +
                            "Total: %,d | RPM: %.1f | Buffer: %d",
                    targetPps, pps, mbps, count, currentRpm, bufferIndex));
        }

        lastReportTime = now;
        lastReportCount = count;
    }

    public long getPacketsSent() { return packetsSent.get(); }
    public double getCurrentRpm() { return currentRpm; }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (simulatorThread != null) {
            simulatorThread.interrupt();
        }
        flushSendBuffer();
        if (socket != null) {
            socket.close();
        }
        log.info("UDP Data Simulator stopped. Total packets sent: {}",
                String.format("%,d", packetsSent.get()));
    }
}
