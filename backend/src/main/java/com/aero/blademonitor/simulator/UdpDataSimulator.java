package com.aero.blademonitor.simulator;

import com.aero.blademonitor.config.AppProperties;
import com.aero.blademonitor.parser.HexStreamParser;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.simulator.enabled", havingValue = "true", matchIfMissing = false)
public class UdpDataSimulator {

    private final AppProperties appProperties;

    private DatagramSocket socket;
    private Thread simulatorThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AtomicInteger sequence = new AtomicInteger(0);
    private final AtomicLong packetsSent = new AtomicLong(0);
    private long startTimeMs;

    private double currentRpm = 0;
    private double targetRpm = 8000;
    private long lastRampTime = 0;

    private final int bladeCount;
    private final int packetsPerFrame = 4;

    public UdpDataSimulator(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.bladeCount = appProperties.getData().getBladeCount();
    }

    @PostConstruct
    public void start() {
        try {
            socket = new DatagramSocket();
            socket.setSendBufferSize(104857600);

            running.set(true);
            simulatorThread = new Thread(this::runSimulation, "udp-simulator");
            simulatorThread.setDaemon(true);
            simulatorThread.start();

            log.info("UDP Data Simulator started, sending to port {}", appProperties.getUdp().getPort());
        } catch (SocketException e) {
            log.error("Failed to start UDP simulator", e);
        }
    }

    @Async("udpReceiverExecutor")
    public void runSimulation() {
        startTimeMs = System.currentTimeMillis();
        lastRampTime = startTimeMs;

        int sampleRate = 10000;
        long intervalNs = 1_000_000_000L / sampleRate;
        long nextSendTime = System.nanoTime();

        int bladePhase = 0;
        double simTime = 0;

        while (running.get()) {
            long now = System.nanoTime();
            if (now < nextSendTime) {
                long sleepNs = nextSendTime - now;
                if (sleepNs > 100_000) {
                    try {
                        Thread.sleep(sleepNs / 1_000_000, (int) (sleepNs % 1_000_000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                continue;
            }
            nextSendTime += intervalNs;

            simTime += 1.0 / sampleRate;

            updateRpmRamp();

            for (int p = 0; p < packetsPerFrame; p++) {
                int bladeIndex = bladePhase % bladeCount;

                double rotationalFreqHz = currentRpm / 60.0;
                double bladePassFreq = rotationalFreqHz * bladeCount;

                double strain = generateStrainSignal(
                        simTime,
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

                sendPacket(frame);
                packetsSent.incrementAndGet();

                bladePhase++;
            }

            if (packetsSent.get() % 100000 == 0) {
                log.info("Simulator: {} packets sent, RPM: {}",
                        String.format("%,d", packetsSent.get()),
                        String.format("%,.1f", currentRpm));
            }
        }
    }

    private void updateRpmRamp() {
        long elapsed = System.currentTimeMillis() - lastRampTime;

        if (elapsed > 15000) {
            if (targetRpm < 3000) {
                targetRpm = 6000;
            } else if (targetRpm < 8000) {
                targetRpm = 12000;
            } else if (targetRpm < 13000) {
                targetRpm = 15000;
            } else if (targetRpm < 18000) {
                targetRpm = 2000;
            } else {
                targetRpm = 2000;
            }
            lastRampTime = System.currentTimeMillis();
        }

        if (currentRpm < targetRpm) {
            currentRpm = Math.min(currentRpm + 2.0, targetRpm);
        } else if (currentRpm > targetRpm) {
            currentRpm = Math.max(currentRpm - 2.0, targetRpm);
        }
    }

    private double generateStrainSignal(double time, int bladeIndex,
                                         double rotationalFreq, double bladePassFreq) {
        double phase = 2.0 * Math.PI * bladeIndex / bladeCount;
        double rpmSweepPhase = time * 0.05;

        double signal = 0;

        signal += 150.0 * Math.sin(2.0 * Math.PI * rotationalFreq * time + phase);

        signal += 80.0 * Math.sin(2.0 * Math.PI * 2 * rotationalFreq * time + phase * 2);
        signal += 40.0 * Math.sin(2.0 * Math.PI * 3 * rotationalFreq * time + phase * 3);
        signal += 20.0 * Math.sin(2.0 * Math.PI * 4 * rotationalFreq * time + phase * 4);

        signal += 60.0 * Math.sin(2.0 * Math.PI * bladePassFreq * time + phase);
        signal += 25.0 * Math.sin(2.0 * Math.PI * 2 * bladePassFreq * time);

        signal += 5.0 * Math.sin(2.0 * Math.PI * (100 + bladeIndex * 7) * time);
        signal += 3.0 * Math.sin(2.0 * Math.PI * (350 + bladeIndex * 3) * time);

        signal += (Math.random() - 0.5) * 20.0;

        signal += 50.0 * Math.sin(2.0 * Math.PI * (400 + 50 * Math.sin(rpmSweepPhase)) * time);

        return signal;
    }

    private void sendPacket(byte[] data) {
        try {
            DatagramPacket packet = new DatagramPacket(
                    data,
                    data.length,
                    InetAddress.getByName("127.0.0.1"),
                    appProperties.getUdp().getPort()
            );
            socket.send(packet);
        } catch (IOException e) {
            log.debug("Simulator send error", e);
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (simulatorThread != null) {
            simulatorThread.interrupt();
        }
        if (socket != null) {
            socket.close();
        }
        log.info("UDP Data Simulator stopped. Total packets sent: {}",
                String.format("%,d", packetsSent.get()));
    }
}
