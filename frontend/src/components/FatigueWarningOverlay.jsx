import { useEffect, useRef, useState } from 'react';
import './FatigueWarningOverlay.css';

export default function FatigueWarningOverlay({ warning, onAcknowledge, onShutdown }) {
  const canvasRef = useRef(null);
  const animRef = useRef(null);
  const [pulseIntensity, setPulseIntensity] = useState(0);

  const active = warning?.active && warning?.severity === 'CRITICAL';

  useEffect(() => {
    if (!active) {
      setPulseIntensity(0);
      return;
    }

    let t = 0;
    const pulse = () => {
      t += 0.08;
      const intensity = (Math.sin(t) + 1) / 2;
      setPulseIntensity(intensity);
      animRef.current = requestAnimationFrame(pulse);
    };
    animRef.current = requestAnimationFrame(pulse);

    return () => {
      if (animRef.current) cancelAnimationFrame(animRef.current);
    };
  }, [active]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !active || !warning?.strainHistory?.length) return;

    const ctx = canvas.getContext('2d');
    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();

    canvas.width = rect.width * dpr;
    canvas.height = rect.height * dpr;
    ctx.scale(dpr, dpr);

    const data = warning.strainHistory;
    const w = rect.width;
    const h = rect.height;
    const padL = 50;
    const padR = 20;
    const padT = 20;
    const padB = 30;
    const plotW = w - padL - padR;
    const plotH = h - padT - padB;

    ctx.clearRect(0, 0, w, h);

    ctx.fillStyle = 'rgba(10, 25, 41, 0.9)';
    ctx.fillRect(0, 0, w, h);

    ctx.strokeStyle = 'rgba(255, 230, 0, 0.1)';
    ctx.lineWidth = 1;
    for (let i = 0; i <= 4; i++) {
      const y = padT + (plotH / 4) * i;
      ctx.beginPath();
      ctx.moveTo(padL, y);
      ctx.lineTo(w - padR, y);
      ctx.stroke();
    }

    let minV = Infinity;
    let maxV = -Infinity;
    for (const v of data) {
      if (v < minV) minV = v;
      if (v > maxV) maxV = v;
    }
    const range = maxV - minV || 1;
    const margin = range * 0.1;
    minV -= margin;
    maxV += margin;

    const peakVal = maxV - margin;
    const peakY = padT + plotH - ((peakVal - minV) / (maxV - minV)) * plotH;

    ctx.strokeStyle = 'rgba(255, 99, 132, 0.6)';
    ctx.lineWidth = 1;
    ctx.setLineDash([4, 4]);
    ctx.beginPath();
    ctx.moveTo(padL, peakY);
    ctx.lineTo(w - padR, peakY);
    ctx.stroke();
    ctx.setLineDash([]);

    ctx.fillStyle = '#ff6384';
    ctx.font = 'bold 11px monospace';
    ctx.textAlign = 'left';
    ctx.fillText(`PEAK: ${peakVal.toFixed(1)} με`, padL + 5, peakY - 5);

    ctx.strokeStyle = '#ffd700';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    const len = data.length;
    for (let i = 0; i < len; i++) {
      const x = padL + (i / (len - 1)) * plotW;
      const y = padT + plotH - ((data[i] - minV) / (maxV - minV)) * plotH;
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.stroke();

    const grad = ctx.createLinearGradient(0, padT, 0, padT + plotH);
    grad.addColorStop(0, 'rgba(255, 215, 0, 0.3)');
    grad.addColorStop(1, 'rgba(255, 215, 0, 0)');
    ctx.fillStyle = grad;
    ctx.lineTo(padL + plotW, padT + plotH);
    ctx.lineTo(padL, padT + plotH);
    ctx.closePath();
    ctx.fill();

    ctx.strokeStyle = 'rgba(255, 230, 0, 0.3)';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(padL, padT);
    ctx.lineTo(padL, padT + plotH);
    ctx.stroke();

    ctx.fillStyle = 'rgba(255, 230, 0, 0.7)';
    ctx.font = '10px monospace';
    ctx.textAlign = 'right';
    for (let i = 0; i <= 4; i++) {
      const y = padT + (plotH / 4) * i;
      const val = maxV - ((maxV - minV) / 4) * i;
      ctx.fillText(val.toFixed(0), padL - 5, y + 3);
    }

    ctx.fillStyle = 'rgba(255, 230, 0, 0.5)';
    ctx.textAlign = 'center';
    ctx.fillText('Sample Index', padL + plotW / 2, h - 8);

  }, [active, warning?.strainHistory]);

  if (!active) return null;

  const borderWidth = 4 + pulseIntensity * 8;

  return (
    <div className="fatigue-warning-overlay">
      <div
        className="warning-border"
        style={{
          boxShadow: `inset 0 0 ${30 + pulseIntensity * 50}px rgba(255, 230, 0, ${0.15 + pulseIntensity * 0.25})`,
        }}
      >
        <div className="border-line top" style={{ height: borderWidth }} />
        <div className="border-line right" style={{ width: borderWidth }} />
        <div className="border-line bottom" style={{ height: borderWidth }} />
        <div className="border-line left" style={{ width: borderWidth }} />
      </div>

      <div className="warning-modal">
        <div className="modal-header">
          <div className="warning-icon">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#ffd700" strokeWidth="2">
              <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
              <line x1="12" y1="9" x2="12" y2="13" />
              <line x1="12" y1="17" x2="12.01" y2="17" />
            </svg>
          </div>
          <h2>疲劳损伤临界警报</h2>
          <p>FATIGUE DAMAGE CRITICAL ALERT</p>
        </div>

        <div className="modal-body">
          <div className="damage-metrics">
            <div className="metric">
              <div className="metric-label">叶片编号</div>
              <div className="metric-value critical">#{warning.bladeIndex}</div>
            </div>
            <div className="metric">
              <div className="metric-label">累积损伤度</div>
              <div className="metric-value critical">
                {(warning.damageRatio * 100).toFixed(2)}%
              </div>
              <div className="metric-bar">
                <div
                  className="metric-bar-fill"
                  style={{
                    width: `${Math.min(100, warning.damageRatio * 100)}%`,
                    background: warning.damageRatio >= 0.85
                      ? 'linear-gradient(90deg, #ff6384, #ff0040)'
                      : 'linear-gradient(90deg, #ffd700, #ff8c00)'
                  }}
                />
                <div
                  className="threshold-marker critical"
                  style={{ left: `${warning.damageCriticalThreshold * 100}%` }}
                  title={`临界阈值 ${(warning.damageCriticalThreshold * 100).toFixed(0)}%`}
                />
                <div
                  className="threshold-marker warning"
                  style={{ left: `${warning.damageWarningThreshold * 100}%` }}
                  title={`预警阈值 ${(warning.damageWarningThreshold * 100).toFixed(0)}%`}
                />
              </div>
            </div>
            <div className="metric">
              <div className="metric-label">最大应变幅值</div>
              <div className="metric-value warning">{warning.maxAmplitude?.toFixed(1)} με</div>
            </div>
            <div className="metric">
              <div className="metric-label">雨流循环计数</div>
              <div className="metric-value">{warning.cycleCount?.toLocaleString()}</div>
            </div>
          </div>

          <div className="waveform-section">
            <div className="section-title">
              <span>瞬态载荷峰值波形</span>
              <span className="section-subtitle">TRANSIENT PEAK LOAD WAVEFORM</span>
            </div>
            <canvas ref={canvasRef} className="waveform-canvas" />
          </div>

          <div className="warning-message">
            <p>
              ⚠️ 叶片 #{warning.bladeIndex} 疲劳累积损伤已突破
              <strong> {(warning.damageCriticalThreshold * 100).toFixed(0)}% </strong>
              临界阈值！建议立即停机检查，避免叶片断裂风险。
            </p>
          </div>
        </div>

        <div className="modal-footer">
          <button className="btn-shutdown" onClick={onShutdown}>
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M18.36 6.64a9 9 0 1 1-12.73 0" />
              <line x1="12" y1="2" x2="12" y2="12" />
            </svg>
            确认停机
          </button>
          <button className="btn-acknowledge" onClick={onAcknowledge}>
            已知晓，继续监控
          </button>
        </div>
      </div>
    </div>
  );
}
