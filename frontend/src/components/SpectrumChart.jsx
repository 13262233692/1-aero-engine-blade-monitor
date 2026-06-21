import { useEffect, useRef } from 'react';

export default function SpectrumChart({ spectrumData, maxFreq = 2000 }) {
  const canvasRef = useRef(null);
  const containerRef = useRef(null);
  const animRef = useRef(null);
  const stateRef = useRef({});

  useEffect(() => {
    const canvas = canvasRef.current;
    const container = containerRef.current;
    if (!canvas || !container) return;

    const ctx = canvas.getContext('2d');

    function resize() {
      const rect = container.getBoundingClientRect();
      const dpr = window.devicePixelRatio || 1;
      canvas.width = Math.floor(rect.width * dpr);
      canvas.height = Math.floor(rect.height * dpr);
      canvas.style.width = rect.width + 'px';
      canvas.style.height = rect.height + 'px';
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      stateRef.current.W = rect.width;
      stateRef.current.H = rect.height;
    }
    resize();
    const ro = new ResizeObserver(resize);
    ro.observe(container);

    function render() {
      draw(ctx, stateRef.current);
      animRef.current = requestAnimationFrame(render);
    }
    animRef.current = requestAnimationFrame(render);

    return () => {
      ro.disconnect();
      if (animRef.current) cancelAnimationFrame(animRef.current);
    };
  }, []);

  useEffect(() => {
    stateRef.current.spectrum = spectrumData;
    stateRef.current.maxFreq = maxFreq;
  }, [spectrumData, maxFreq]);

  return (
    <div className="canvas-host" ref={containerRef}>
      <canvas ref={canvasRef} />
    </div>
  );
}

function draw(ctx, state) {
  const W = state.W || 0;
  const H = state.H || 0;
  if (!W || !H) return;

  const padL = 50, padR = 16, padT = 16, padB = 32;
  const plotW = W - padL - padR;
  const plotH = H - padT - padB;

  ctx.clearRect(0, 0, W, H);

  const spec = state.spectrum || {};
  const freqs = spec.frequencies;
  const mags = spec.magnitudes;
  const maxFreq = state.maxFreq || 2000;
  const fundamental = spec.fundamental || 0;

  ctx.fillStyle = 'rgba(0, 212, 255, 0.02)';
  ctx.fillRect(padL, padT, plotW, plotH);

  ctx.strokeStyle = 'rgba(30, 73, 118, 0.3)';
  ctx.lineWidth = 0.5;
  const freqStep = maxFreq > 1500 ? 500 : 200;
  for (let f = 0; f <= maxFreq; f += freqStep) {
    const x = padL + (f / maxFreq) * plotW;
    ctx.beginPath();
    ctx.moveTo(x, padT);
    ctx.lineTo(x, padT + plotH);
    ctx.stroke();
  }
  for (let i = 0; i <= 4; i++) {
    const y = padT + (i / 4) * plotH;
    ctx.beginPath();
    ctx.moveTo(padL, y);
    ctx.lineTo(padL + plotW, y);
    ctx.stroke();
  }

  ctx.strokeStyle = 'rgba(30, 73, 118, 0.9)';
  ctx.lineWidth = 1;
  ctx.strokeRect(padL + 0.5, padT + 0.5, plotW - 1, plotH - 1);

  const history = spec.history || [];
  for (let i = Math.max(0, history.length - 8); i < history.length; i++) {
    const h = history[i];
    if (!h.frequencies || !h.magnitudes) continue;
    const age = history.length - i;
    const alpha = 0.05 + (1 - age / 10) * 0.2;
    drawSpectrumLine(ctx, h.frequencies, h.magnitudes, maxFreq,
      padL, padT, plotW, plotH, `rgba(0, 212, 255, ${alpha})`, 0.8);
  }

  if (freqs && mags && freqs.length > 0) {
    const grad = ctx.createLinearGradient(0, padT, 0, padT + plotH);
    grad.addColorStop(0, 'rgba(0, 255, 157, 0.4)');
    grad.addColorStop(0.5, 'rgba(0, 212, 255, 0.2)');
    grad.addColorStop(1, 'rgba(0, 212, 255, 0.0)');
    drawSpectrumFill(ctx, freqs, mags, maxFreq, padL, padT, plotW, plotH, grad);
    drawSpectrumLine(ctx, freqs, mags, maxFreq, padL, padT, plotW, plotH, '#00ff9d', 1.8);
  }

  if (fundamental > 0 && fundamental < maxFreq) {
    const fx = padL + (fundamental / maxFreq) * plotW;
    ctx.setLineDash([4, 4]);
    ctx.strokeStyle = 'rgba(255, 215, 0, 0.8)';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(fx, padT);
    ctx.lineTo(fx, padT + plotH);
    ctx.stroke();
    ctx.setLineDash([]);

    ctx.fillStyle = 'rgba(255, 215, 0, 0.9)';
    ctx.font = 'bold 10px Consolas, monospace';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'top';
    ctx.fillText(`f₁ = ${fundamental.toFixed(1)} Hz`, fx, padT + 4);
  }

  ctx.fillStyle = 'var(--text-muted)';
  ctx.font = '10px Consolas, monospace';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'top';
  for (let f = 0; f <= maxFreq; f += freqStep) {
    const x = padL + (f / maxFreq) * plotW;
    ctx.fillText(f + '', x, padT + plotH + 8);
  }

  ctx.textAlign = 'right';
  ctx.textBaseline = 'middle';
  const labels = ['峰值', '75%', '50%', '25%', '0'];
  for (let i = 0; i < 5; i++) {
    const y = padT + (i / 4) * plotH;
    ctx.fillText(labels[i], padL - 6, y);
  }

  ctx.fillStyle = 'var(--accent-cyan)';
  ctx.font = '11px -apple-system, sans-serif';
  ctx.textAlign = 'center';
  ctx.fillText('频率 (Hz)', padL + plotW / 2, H - 8);
}

function findMaxMag(mags) {
  let max = 1e-6;
  for (const m of mags) if (m > max) max = m;
  return max * 1.05;
}

function drawSpectrumLine(ctx, freqs, mags, maxFreq, padL, padT, plotW, plotH, color, lw) {
  const maxMag = findMaxMag(mags);
  ctx.strokeStyle = color;
  ctx.lineWidth = lw;
  ctx.beginPath();
  let started = false;
  const n = freqs.length;
  for (let i = 0; i < n; i++) {
    const f = freqs[i];
    if (f > maxFreq) break;
    const x = padL + (f / maxFreq) * plotW;
    const y = padT + plotH - (Math.min(mags[i], maxMag) / maxMag) * plotH;
    if (!started) { ctx.moveTo(x, y); started = true; }
    else ctx.lineTo(x, y);
  }
  ctx.stroke();
}

function drawSpectrumFill(ctx, freqs, mags, maxFreq, padL, padT, plotW, plotH, color) {
  const maxMag = findMaxMag(mags);
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.moveTo(padL, padT + plotH);
  const n = freqs.length;
  for (let i = 0; i < n; i++) {
    const f = freqs[i];
    if (f > maxFreq) break;
    const x = padL + (f / maxFreq) * plotW;
    const y = padT + plotH - (Math.min(mags[i], maxMag) / maxMag) * plotH;
    ctx.lineTo(x, y);
  }
  const lastX = Math.min(padL + plotW, padL + (freqs[n - 1] / maxFreq) * plotW);
  ctx.lineTo(lastX, padT + plotH);
  ctx.closePath();
  ctx.fill();
}
