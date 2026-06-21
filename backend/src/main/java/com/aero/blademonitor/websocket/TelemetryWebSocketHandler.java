package com.aero.blademonitor.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class TelemetryWebSocketHandler extends TextWebSocketHandler {

    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger clientCount = new AtomicInteger(0);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String id = session.getId();
        sessions.put(id, session);
        int count = clientCount.incrementAndGet();
        log.info("WebSocket client connected: id={}, remote={}, totalClients={}",
                id, session.getRemoteAddress(), count);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String id = session.getId();
        sessions.remove(id);
        int count = clientCount.decrementAndGet();
        log.info("WebSocket client disconnected: id={}, status={}, totalClients={}",
                id, status, count);
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        log.debug("Received message from client {}: {}", session.getId(), payload);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("WebSocket transport error for session {}: {}",
                session.getId(), exception.getMessage());
    }

    public void broadcast(String json) {
        if (sessions.isEmpty() || json == null) return;

        Iterator<WebSocketSession> it = sessions.values().iterator();
        while (it.hasNext()) {
            WebSocketSession session = it.next();
            if (session.isOpen()) {
                try {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(json));
                    }
                } catch (IOException e) {
                    log.debug("Failed to send to session {}: {}", session.getId(), e.getMessage());
                    try {
                        session.close(CloseStatus.SESSION_NOT_RELIABLE);
                    } catch (Exception ignored) {}
                    it.remove();
                    clientCount.decrementAndGet();
                }
            } else {
                it.remove();
                clientCount.decrementAndGet();
            }
        }
    }

    public int getClientCount() {
        return clientCount.get();
    }

    public boolean hasClients() {
        return !sessions.isEmpty();
    }
}
