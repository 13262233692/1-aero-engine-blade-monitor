import { useEffect, useRef, useState, useCallback } from 'react';

const WS_URLS = [
  () => `ws://${window.location.hostname}:8080/ws/telemetry`,
  () => `ws://${window.location.host}/ws/telemetry`,
  () => 'ws://localhost:8080/ws/telemetry',
];

const MAX_CAMPBELL_POINTS = 10000;
const MAX_SPECTRUM_HISTORY = 60;
const MAX_FRAME_HISTORY = 500;

export function useTelemetryWebSocket() {
  const wsRef = useRef(null);
  const reconnectTimerRef = useRef(null);
  const urlIndexRef = useRef(0);
  const manualCloseRef = useRef(false);

  const [connected, setConnected] = useState(false);
  const [connectionStatus, setConnectionStatus] = useState('connecting');

  const [frameData, setFrameData] = useState({
    rpm: 0, strain: 0, temperature: 0,
    bladeIndex: 0, bladeCount: 24,
    timestamp: 0
  });

  const [spectrumData, setSpectrumData] = useState({
    frequencies: null,
    magnitudes: null,
    fundamental: 0,
    amplitude: 0,
    rpm: 0,
    bladeIndex: 0,
    timestamp: 0,
    history: []
  });

  const campbellPointsRef = useRef([]);
  const [campbellSnapshot, setCampbellSnapshot] = useState([]);

  const frameHistoryRef = useRef([]);
  const [frameHistorySnapshot, setFrameHistorySnapshot] = useState([]);

  const [metrics, setMetrics] = useState({
    packetsReceived: 0,
    packetsDropped: 0,
    framesParsed: 0,
    fftComputed: 0,
    packetsPerSecond: 0,
    avgProcessingLatencyMs: 0,
    bufferSize: 0,
    wsClientCount: 0
  });

  const connect = useCallback(() => {
    manualCloseRef.current = false;

    if (wsRef.current && (wsRef.current.readyState === WebSocket.OPEN
        || wsRef.current.readyState === WebSocket.CONNECTING)) {
      return;
    }

    const urlFactory = WS_URLS[urlIndexRef.current % WS_URLS.length];
    const url = urlFactory();
    urlIndexRef.current++;

    setConnectionStatus('connecting');

    try {
      const ws = new WebSocket(url);
      wsRef.current = ws;

      ws.onopen = () => {
        setConnected(true);
        setConnectionStatus('connected');
        if (reconnectTimerRef.current) {
          clearTimeout(reconnectTimerRef.current);
          reconnectTimerRef.current = null;
        }
      };

      ws.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data);
          handleMessage(msg);
        } catch (e) {
          console.warn('Failed to parse WS message:', e);
        }
      };

      ws.onerror = (err) => {
        console.warn('WebSocket error:', err);
      };

      ws.onclose = () => {
        setConnected(false);
        setConnectionStatus('disconnected');
        if (!manualCloseRef.current) {
          scheduleReconnect();
        }
      };
    } catch (e) {
      console.error('Failed to create WebSocket:', e);
      scheduleReconnect();
    }
  }, []);

  const scheduleReconnect = useCallback(() => {
    if (reconnectTimerRef.current) return;
    reconnectTimerRef.current = setTimeout(() => {
      reconnectTimerRef.current = null;
      connect();
    }, 2000);
  }, [connect]);

  const handleMessage = useCallback((msg) => {
    if (!msg || !msg.type) return;

    switch (msg.type) {
      case 'frame':
        handleFrame(msg);
        break;
      case 'spectrum':
        handleSpectrum(msg);
        break;
      case 'campbell':
        handleCampbell(msg);
        break;
      case 'metrics':
        handleMetrics(msg);
        break;
      default:
        break;
    }
  }, []);

  const handleFrame = useCallback((msg) => {
    setFrameData(prev => ({
      rpm: msg.rpm ?? prev.rpm,
      strain: msg.strain ?? prev.strain,
      temperature: msg.temperature ?? prev.temperature,
      bladeIndex: msg.bladeIndex ?? prev.bladeIndex,
      bladeCount: msg.bladeCount ?? prev.bladeCount,
      timestamp: msg.timestamp ?? Date.now()
    }));

    const hist = frameHistoryRef.current;
    hist.push({
      rpm: msg.rpm,
      strain: msg.strain,
      temperature: msg.temperature,
      bladeIndex: msg.bladeIndex,
      timestamp: msg.timestamp ?? Date.now()
    });
    while (hist.length > MAX_FRAME_HISTORY) hist.shift();
  }, []);

  const handleSpectrum = useCallback((msg) => {
    setSpectrumData(prev => {
      const newHistory = [...prev.history];
      newHistory.push({
        frequencies: msg.spectrumFrequencies,
        magnitudes: msg.spectrumMagnitudes,
        fundamental: msg.fundamentalFrequency,
        amplitude: msg.firstOrderAmplitude,
        rpm: msg.rpm,
        timestamp: msg.timestamp
      });
      while (newHistory.length > MAX_SPECTRUM_HISTORY) newHistory.shift();

      return {
        frequencies: msg.spectrumFrequencies ?? prev.frequencies,
        magnitudes: msg.spectrumMagnitudes ?? prev.magnitudes,
        fundamental: msg.fundamentalFrequency ?? prev.fundamental,
        amplitude: msg.firstOrderAmplitude ?? prev.amplitude,
        rpm: msg.rpm ?? prev.rpm,
        bladeIndex: msg.bladeIndex ?? prev.bladeIndex,
        timestamp: msg.timestamp ?? Date.now(),
        history: newHistory
      };
    });
  }, []);

  const handleCampbell = useCallback((msg) => {
    if (!msg.campbellPoints || !Array.isArray(msg.campbellPoints)) return;

    const pts = campbellPointsRef.current;
    for (const p of msg.campbellPoints) {
      pts.push(p);
    }
    while (pts.length > MAX_CAMPBELL_POINTS) pts.shift();
  }, []);

  const handleMetrics = useCallback((msg) => {
    if (msg.metrics) {
      setMetrics({
        packetsReceived: msg.metrics.packetsReceived ?? 0,
        packetsDropped: msg.metrics.packetsDropped ?? 0,
        framesParsed: msg.metrics.framesParsed ?? 0,
        fftComputed: msg.metrics.fftComputed ?? 0,
        packetsPerSecond: msg.metrics.packetsPerSecond ?? 0,
        avgProcessingLatencyMs: msg.metrics.avgProcessingLatencyMs ?? 0,
        bufferSize: msg.metrics.bufferSize ?? 0,
        wsClientCount: msg.metrics.wsClientCount ?? 0
      });
    }
  }, []);

  useEffect(() => {
    const interval = setInterval(() => {
      if (campbellPointsRef.current.length > 0) {
        setCampbellSnapshot([...campbellPointsRef.current]);
      }
      if (frameHistoryRef.current.length > 0) {
        setFrameHistorySnapshot([...frameHistoryRef.current]);
      }
    }, 50);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    connect();
    return () => {
      manualCloseRef.current = true;
      if (reconnectTimerRef.current) {
        clearTimeout(reconnectTimerRef.current);
      }
      if (wsRef.current) {
        try { wsRef.current.close(); } catch (e) {}
      }
    };
  }, [connect]);

  const clearAllData = useCallback(() => {
    campbellPointsRef.current = [];
    frameHistoryRef.current = [];
    setCampbellSnapshot([]);
    setFrameHistorySnapshot([]);
    setSpectrumData(prev => ({ ...prev, history: [] }));
  }, []);

  return {
    connected,
    connectionStatus,
    frameData,
    spectrumData,
    campbellPoints: campbellSnapshot,
    frameHistory: frameHistorySnapshot,
    metrics,
    clearAllData
  };
}
