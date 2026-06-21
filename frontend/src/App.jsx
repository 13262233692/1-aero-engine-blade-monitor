import React, { useMemo } from 'react';
import { useTelemetryWebSocket } from './hooks/useTelemetryWebSocket';
import CampbellDiagram from './components/CampbellDiagram';
import SpectrumChart from './components/SpectrumChart';
import StrainWaveform from './components/StrainWaveform';
import FatigueWarningOverlay from './components/FatigueWarningOverlay';

function formatNum(n, decimals = 0) {
  if (n === null || n === undefined || isNaN(n)) return '-';
  if (Math.abs(n) >= 1e6) return (n / 1e6).toFixed(2) + 'M';
  if (Math.abs(n) >= 1e3) return (n / 1e3).toFixed(1) + 'k';
  return Number(n).toFixed(decimals);
}

export default function App() {
  const {
    connected,
    connectionStatus,
    frameData,
    spectrumData,
    campbellPoints,
    frameHistory,
    metrics,
    fatigueData,
    fatigueWarning,
    acknowledgeFatigueWarning,
    clearAllData
  } = useTelemetryWebSocket();

  const statusText = useMemo(() => {
    switch (connectionStatus) {
      case 'connected': return '实时连接 LIVE';
      case 'connecting': return '连接中…';
      case 'disconnected': return '已断开';
      default: return '未知';
    }
  }, [connectionStatus]);

  const pointsCount = campbellPoints?.length || 0;

  return (
    <div className="app-container">
      <header className="app-header">
        <div className="header-left">
          <div className="logo-icon">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="#0a1929" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="2.8" fill="#0a1929" stroke="none" />
              <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" strokeWidth="2" />
              <path d="M12 6a6 6 0 0 1 0 12" strokeWidth="1.8" opacity="0.7" />
              <path d="M12 18a6 6 0 0 1 0-12" strokeWidth="1.8" opacity="0.4" />
            </svg>
          </div>
          <div className="title-group">
            <h1>航空发动机叶片动应力实时遥测系统</h1>
            <p>AERO-ENGINE BLADE DYNAMIC STRESS TELEMETRY · CAMPBELL ANALYZER</p>
          </div>
        </div>

        <div className="header-right">
          <div className={`status-badge ${connected ? 'connected' : 'disconnected'}`}>
            <span className="status-dot" />
            <span>{statusText}</span>
          </div>
          <div className="status-badge" style={{
            background: 'rgba(0, 212, 255, 0.08)',
            border: '1px solid rgba(0, 212, 255, 0.3)',
            color: 'var(--accent-cyan)'
          }}>
            <span style={{
              width: '8px', height: '8px', borderRadius: '50%',
              background: 'var(--accent-cyan)',
              boxShadow: '0 0 10px var(--accent-cyan)'
            }} />
            <span>坎贝尔点: {formatNum(pointsCount)}</span>
          </div>
          <div className="status-badge" style={{
            background: 'rgba(179, 102, 255, 0.08)',
            border: '1px solid rgba(179, 102, 255, 0.3)',
            color: 'var(--accent-purple)'
          }}>
            <span style={{
              width: '8px', height: '8px', borderRadius: '50%',
              background: 'var(--accent-purple)',
              boxShadow: '0 0 10px var(--accent-purple)'
            }} />
            <span>客户端: {metrics.wsClientCount || 0}</span>
          </div>
          <button
            onClick={clearAllData}
            style={{
              padding: '6px 14px',
              borderRadius: '20px',
              background: 'rgba(255, 61, 87, 0.1)',
              border: '1px solid rgba(255, 61, 87, 0.3)',
              color: 'var(--accent-red)',
              fontFamily: 'var(--font-mono)',
              fontSize: '12px',
              fontWeight: '600',
              cursor: 'pointer'
            }}
          >
            清空数据
          </button>
        </div>
      </header>

      <div className="app-body">
        <div className="panel panel-metrics">
          <div className="panel-header">
            <div className="panel-title">实时参数 · LIVE METRICS</div>
            <div className="panel-value">
              UPDATED: {new Date().toLocaleTimeString('zh-CN', { hour12: false })}
            </div>
          </div>
          <div className="panel-body">
            <div className="metrics-grid">
              <div className="metric-item">
                <div className="metric-label">主轴转速</div>
                <div className="metric-value rpm">
                  {formatNum(frameData?.rpm, 0)}
                </div>
                <div className="metric-unit">RPM · rev/min</div>
              </div>
              <div className="metric-item">
                <div className="metric-label">旋转频率</div>
                <div className="metric-value freq">
                  {frameData?.rpm ? (frameData.rpm / 60).toFixed(2) : '-'}
                </div>
                <div className="metric-unit">Hz · 1X Sync</div>
              </div>
              <div className="metric-item">
                <div className="metric-label">一阶振动频率</div>
                <div className="metric-value freq">
                  {spectrumData?.fundamental ? spectrumData.fundamental.toFixed(1) : '-'}
                </div>
                <div className="metric-unit">Hz · 1st Mode</div>
              </div>
              <div className="metric-item">
                <div className="metric-label">叶片通过频率</div>
                <div className="metric-value freq">
                  {frameData?.rpm ? ((frameData.rpm / 60) * (frameData.bladeCount || 24)).toFixed(1) : '-'}
                </div>
                <div className="metric-unit">Hz · BPF {frameData.bladeCount || 24}X</div>
              </div>
              <div className="metric-item">
                <div className="metric-label">瞬时形变量</div>
                <div className="metric-value strain">
                  {frameData?.strain !== undefined && frameData.strain !== null
                    ? (frameData.strain >= 0 ? '+' : '') + frameData.strain.toFixed(1)
                    : '-'}
                </div>
                <div className="metric-unit">microstrain με</div>
              </div>
              <div className="metric-item">
                <div className="metric-label">一阶振幅</div>
                <div className="metric-value amp">
                  {spectrumData?.amplitude ? spectrumData.amplitude.toFixed(2) : '-'}
                </div>
                <div className="metric-unit">με · 1st Amp</div>
              </div>
              <div className="metric-item">
                <div className="metric-label">叶片温度</div>
                <div className="metric-value temp">
                  {frameData?.temperature !== undefined && frameData.temperature !== null
                    ? frameData.temperature.toFixed(1)
                    : '-'}
                </div>
                <div className="metric-unit">°C · Tmeas</div>
              </div>
              <div className="metric-item">
                <div className="metric-label">叶片索引</div>
                <div className="metric-value">
                  #{frameData?.bladeIndex !== undefined ? frameData.bladeIndex : '-'}
                  <span style={{ fontSize: '13px', color: 'var(--text-muted)' }}>
                    /{frameData?.bladeCount || 24}
                  </span>
                </div>
                <div className="metric-unit">Blade Index</div>
              </div>
              <div className="metric-item">
                <div className="metric-label">UDP 接收</div>
                <div className="metric-value pkts">{formatNum(metrics.packetsReceived)}</div>
                <div className="metric-unit">Total Packets</div>
              </div>
              <div className="metric-item">
                <div className="metric-label">丢包率</div>
                <div className="metric-value" style={{
                  color: (metrics.packetsDropped || 0) > 100 ? 'var(--accent-red)' : 'var(--accent-green)'
                }}>
                  {metrics.packetsReceived
                    ? ((metrics.packetsDropped || 0) / (metrics.packetsReceived + metrics.packetsDropped || 1) * 100).toFixed(3)
                    : '0.000'}%
                </div>
                <div className="metric-unit">Drop: {formatNum(metrics.packetsDropped)}</div>
              </div>
              <div className="metric-item">
                <div className="metric-label">解析帧数</div>
                <div className="metric-value pkts">{formatNum(metrics.framesParsed)}</div>
                <div className="metric-unit">Frames Parsed</div>
              </div>
              <div className="metric-item">
                <div className="metric-label">FFT 计算</div>
                <div className="metric-value pkts">{formatNum(metrics.fftComputed)}</div>
                <div className="metric-unit">FFT Windows</div>
              </div>
              <div className="metric-item">
                <div className="metric-label">处理延迟</div>
                <div className="metric-value">
                  {metrics.avgProcessingLatencyMs !== undefined
                    ? metrics.avgProcessingLatencyMs.toFixed(2)
                    : '-'}
                </div>
                <div className="metric-unit">毫秒 ms</div>
              </div>
              <div className="metric-item">
                <div className="metric-label">环形缓冲占用</div>
                <div className="metric-value">
                  {metrics.bufferSize !== undefined ? formatNum(metrics.bufferSize) : '-'}
                </div>
                <div className="metric-unit">Buffer Depth</div>
              </div>
              <div className="metric-item">
                <div className="metric-label">疲劳累积损伤</div>
                <div className="metric-value" style={{
                  color: fatigueData?.severity === 'CRITICAL' ? 'var(--accent-red)'
                    : fatigueData?.severity === 'WARNING' ? '#ffd700'
                    : 'var(--accent-green)'
                }}>
                  {fatigueData?.damageRatio !== undefined
                    ? (fatigueData.damageRatio * 100).toFixed(2)
                    : '0.00'}%
                </div>
                <div className="metric-unit">
                  Miner Damage · #{fatigueData?.bladeIndex ?? 0}
                </div>
              </div>
              <div className="metric-item">
                <div className="metric-label">最大应变幅值</div>
                <div className="metric-value" style={{
                  color: (fatigueData?.maxAmplitude || 0) > 350 ? 'var(--accent-red)'
                    : (fatigueData?.maxAmplitude || 0) > 250 ? '#ffd700'
                    : 'var(--accent-cyan)'
                }}>
                  {fatigueData?.maxAmplitude !== undefined
                    ? fatigueData.maxAmplitude.toFixed(1)
                    : '-'}
                </div>
                <div className="metric-unit">με · Peak Strain</div>
              </div>
              <div className="metric-item">
                <div className="metric-label">雨流循环计数</div>
                <div className="metric-value pkts">{formatNum(fatigueData?.totalCycles)}</div>
                <div className="metric-unit">Rainflow Cycles</div>
              </div>
            </div>
          </div>
        </div>

        <div className="panel panel-campbell">
          <div className="panel-header">
            <div className="panel-title">
              坎贝尔图 · CAMPBELL DIAGRAM ｜ 共振特性分析 · RPM vs. Frequency · Order Track
            </div>
            <div className="panel-value">
              采样率 10 kHz · FFT 1024pt · Hann 窗 · 带通 10~2000 Hz
            </div>
          </div>
          <div className="panel-body">
            <CampbellDiagram
              points={campbellPoints}
              currentRpm={frameData?.rpm || 0}
              maxRpm={20000}
              maxFreq={2000}
            />
          </div>
        </div>

        <div className="panel" style={{ gridColumn: '1', gridRow: '2' }}>
          <div className="panel-header">
            <div className="panel-title">FFT 频谱分析 · REAL-TIME SPECTRUM</div>
            <div className="panel-value">
              f₁ = {spectrumData?.fundamental ? spectrumData.fundamental.toFixed(1) : '-'} Hz
            </div>
          </div>
          <div className="panel-body">
            <SpectrumChart spectrumData={spectrumData} maxFreq={2000} />
          </div>
        </div>

        <div className="panel" style={{ gridColumn: '2', gridRow: '2' }}>
          <div className="panel-header">
            <div className="panel-title">应变片时域波形 · STRAIN TIME-DOMAIN</div>
            <div className="panel-value">
              ε = {frameData?.strain !== undefined && frameData.strain !== null
                ? ((frameData.strain >= 0 ? '+' : '') + frameData.strain.toFixed(1)) : '-'} με
            </div>
          </div>
          <div className="panel-body">
            <StrainWaveform frameHistory={frameHistory} />
          </div>
        </div>
      </div>

      <FatigueWarningOverlay
        warning={fatigueWarning}
        onAcknowledge={acknowledgeFatigueWarning}
        onShutdown={() => {
          if (confirm('确认执行停机操作？此操作将触发安全停机程序。')) {
            acknowledgeFatigueWarning();
          }
        }}
      />
    </div>
  );
}
