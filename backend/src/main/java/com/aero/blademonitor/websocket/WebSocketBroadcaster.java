package com.aero.blademonitor.websocket;

import com.aero.blademonitor.dto.TelemetryMessage;
import com.aero.blademonitor.model.BladeDataFrame;
import com.aero.blademonitor.model.CampbellPoint;
import com.aero.blademonitor.model.FftResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class WebSocketBroadcaster {

    private final TelemetryWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;

    public WebSocketBroadcaster(TelemetryWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    public void broadcastFrame(BladeDataFrame frame) {
        if (!webSocketHandler.hasClients()) return;

        TelemetryMessage msg = TelemetryMessage.builder()
                .type("frame")
                .timestamp(System.currentTimeMillis())
                .rpm(frame.getRpm())
                .bladeIndex(frame.getBladeIndex())
                .bladeCount(frame.getBladeCount())
                .strain(frame.getStrain())
                .temperature(frame.getTemperature())
                .build();

        sendJson(msg);
    }

    public void broadcastFftResult(FftResult result) {
        if (!webSocketHandler.hasClients()) return;

        TelemetryMessage msg = TelemetryMessage.builder()
                .type("spectrum")
                .timestamp(System.currentTimeMillis())
                .rpm(result.getRpm())
                .bladeIndex(result.getBladeIndex())
                .frequency(result.getFundamentalFrequency())
                .amplitude(result.getFirstOrderAmplitude())
                .fundamentalFrequency(result.getFundamentalFrequency())
                .firstOrderAmplitude(result.getFirstOrderAmplitude())
                .spectrumFrequencies(result.getFrequencies())
                .spectrumMagnitudes(result.getMagnitudes())
                .build();

        sendJson(msg);
    }

    public void broadcastCampbellBatch(List<CampbellPoint> points) {
        if (!webSocketHandler.hasClients() || points.isEmpty()) return;

        TelemetryMessage msg = TelemetryMessage.builder()
                .type("campbell")
                .timestamp(System.currentTimeMillis())
                .campbellPoints(points)
                .build();

        sendJson(msg);
    }

    public void broadcastMetrics(
            long packetsReceived,
            long packetsDropped,
            long framesParsed,
            long fftComputed,
            double packetsPerSecond,
            double avgProcessingLatencyMs,
            int bufferSize,
            int wsClientCount
    ) {
        if (!webSocketHandler.hasClients()) return;

        TelemetryMessage.MetricsDto metrics = TelemetryMessage.MetricsDto.builder()
                .packetsReceived(packetsReceived)
                .packetsDropped(packetsDropped)
                .framesParsed(framesParsed)
                .fftComputed(fftComputed)
                .packetsPerSecond(packetsPerSecond)
                .avgProcessingLatencyMs(avgProcessingLatencyMs)
                .bufferSize(bufferSize)
                .wsClientCount(wsClientCount)
                .build();

        TelemetryMessage msg = TelemetryMessage.builder()
                .type("metrics")
                .timestamp(System.currentTimeMillis())
                .metrics(metrics)
                .build();

        sendJson(msg);
    }

    private void sendJson(TelemetryMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            webSocketHandler.broadcast(json);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize telemetry message", e);
        }
    }

    public int getClientCount() {
        return webSocketHandler.getClientCount();
    }

    public boolean hasClients() {
        return webSocketHandler.hasClients();
    }

    public void broadcastJson(TelemetryMessage message) {
        sendJson(message);
    }

    public void broadcastRawJson(String json) {
        try {
            webSocketHandler.broadcast(json);
        } catch (Exception e) {
            log.warn("Failed to broadcast raw JSON", e);
        }
    }
}
