package com.aero.blademonitor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private UdpProperties udp = new UdpProperties();
    private BufferProperties buffer = new BufferProperties();
    private FftProperties fft = new FftProperties();
    private WebsocketProperties websocket = new WebsocketProperties();
    private DataProperties data = new DataProperties();
    private SimulatorProperties simulator = new SimulatorProperties();
    private PersistenceProperties persistence = new PersistenceProperties();
    private FatigueProperties fatigue = new FatigueProperties();

    @Data
    public static class UdpProperties {
        private int port = 9000;
        private int bufferSize = 65536;
        private int socketReceiveBuffer = 104857600;
        private long selectorTimeoutMs = 10;
    }

    @Data
    public static class BufferProperties {
        private int ringBufferSize = 65536;
    }

    @Data
    public static class FftProperties {
        private int windowSize = 1024;
        private double overlap = 0.5;
        private double sampleRate = 10000.0;
        private double lowCutFreq = 10.0;
        private double highCutFreq = 2000.0;
    }

    @Data
    public static class WebsocketProperties {
        private long broadcastIntervalMs = 16;
        private int maxClients = 32;
    }

    @Data
    public static class DataProperties {
        private int bladeCount = 24;
        private int maxRpm = 20000;
    }

    @Data
    public static class SimulatorProperties {
        private boolean enabled = true;
        private int targetPps = 150000;
        private boolean rpmRampMode = true;
    }

    @Data
    public static class PersistenceProperties {
        private boolean enabled = true;
        private int batchSize = 1000;
        private int maxQueueSize = 100000;
        private long flushIntervalMs = 500;
        private double rpmThreshold = 1000;
    }

    @Data
    public static class FatigueProperties {
        private boolean enabled = true;
        private double damageWarningThreshold = 0.7;
        private double damageCriticalThreshold = 0.85;
        private double maxAmplitudeWarning = 400.0;
        private double maxAmplitudeCritical = 600.0;
        private double fatigueLimitStrain = 500.0;
        private double cyclesToFailure = 10000000.0;
        private double snExponent = 5.0;
        private int minCycleBatchSize = 100;
        private long warningCooldownMs = 5000;
    }
}
