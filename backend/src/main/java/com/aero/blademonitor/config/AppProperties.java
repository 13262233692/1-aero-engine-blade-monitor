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
}
