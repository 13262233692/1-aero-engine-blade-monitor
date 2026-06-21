import { useEffect, useRef } from 'react';

export default function StrainWaveform({ frameHistory }) {
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
    stateRef.current.frameHistory = frameHistory;
  }, [frameHistory]);

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

  const hist = state.frameHistory || [];
  const strains = hist.map(h => h?.strain ?? 0);

  ctx.fillStyle = 'rgba(255, 215, 0, 0.02)';
  ctx.fillRect(padL, padT, plotW, plotH);

  ctx.strokeStyle = 'rgba(30, 73, 118, 0.3)';
  ctx.lineWidth = 0.5;
  for (let i = 0; i <= 4; i++) {
    const y = padT + (i / 4) * plotH;
    ctx.beginPath();
    ctx.moveTo(padL, y);
    ctx.lineTo(padL + plotW, y);
    ctx.stroke();
  }
  for (let i = 0; i <= 8; i++) {
    const x = padL + (i / 8) * plotW;
    ctx.beginPath();
    ctx.moveTo(x, padT);
    ctx.lineTo(x, padT + plotH);
    ctx.stroke();
  }

  ctx.setLineDash([3, 3]);
  ctx.strokeStyle = 'rgba(141, 185, 227, 0.5)';
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(padL, padT + plotH / 2);
  ctx.lineTo(padL + plotW, padT + plotH / 2);
  ctx.stroke();
  ctx.setLineDash([]);

  ctx.strokeStyle = 'rgba(30, 73, 118, 0.9)';
  ctx.lineWidth = 1;
  ctx.strokeRect(padL + 0.5, padT + 0.5, plotW - 1, plotH - 1);

  if (strains.length > 1) {
    let maxAbs = 100;
    for (const s of strains) {
      const a = Math.abs(s);
      if (a > maxAbs) maxAbs = a;
    }
    maxAbs = Math.max(100, maxAbs * 1.1);

    const waveGrad = ctx.createLinearGradient(padL, padT, padL, padT + plotH);
    waveGrad.addColorStop(0, '#ff3d57');
    waveGrad.addColorStop(0.5, '#ffd700');
    waveGrad.addColorStop(1, '#ff3d57');

    ctx.strokeStyle = waveGrad;
    ctx.lineWidth = 1.6;
    ctx.beginPath();

    const n = strains.length;
    for (let i = 0; i < n; i++) {
      const x = padL + (i / (n - 1)) * plotW;
      const norm = Math.max(-1, Math.min(1, strains[i] / maxAbs));
      const y = padT + plotH / 2 - (norm * plotH) / 2;
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.stroke();

    ctx.fillStyle = 'rgba(255, 215, 0, 0.1)';
    ctx.lineTo(padL + plotW, padT + plotH / 2);
    ctx.lineTo(padL, padT + plotH / 2);
    ctx.closePath();
    ctx.fill();

    if (n > 0) {
      const lx = padL + plotW;
      const lastNorm = Math.max(-1, Math.min(1, strains[n - 1] / maxAbs));
      const ly = padT + plotH / 2 - (lastNorm * plotH) / 2;

      ctx.fillStyle = '#ffd700';
      ctx.shadowColor = '#ffd700';
      ctx.shadowBlur = 12;
      ctx.beginPath();
      ctx.arc(lx, ly, 5, 0, Math.PI * 2);
      ctx.fill();
      ctx.shadowBlur = 0;

      ctx.strokeStyle = '#fff';
      ctx.lineWidth = 1;
      ctx.stroke();
    }

    ctx.fillStyle = 'var(--text-muted)';
    ctx.font = '10px Consolas, monospace';
    ctx.textAlign = 'right';
    ctx.textBaseline = 'middle';
    const levels = [maxAbs, maxAbs / 2, 0, -maxAbs / 2, -maxAbs];
    const labels = ['+max', '+½', '0', '-½', '-max'];
    for (let i = 0; i < 5; i++) {
      const y = padT + (i / 4) * plotH;
      ctx.fillText(labels[i], padL - 6, y);
    }

    const latest = strains[strains.length - 1];
    if (latest !== undefined) {
      ctx.fillStyle = '#ffd700';
      ctx.font = 'bold 11px Consolas, monospace';
      ctx.textAlign = 'left';
      ctx.textBaseline = 'top';
      ctx.fillText(`${latest >= 0 ? '+' : ''}${latest.toFixed(1)} με`, padL + 8, padT + 6);
    }
  } else {
    ctx.fillStyle = 'var(--text-muted)';
    ctx.font = '12px Consolas, monospace';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText('等待数据…', padL + plotW / 2, padT + plotH / 2);
  }

  ctx.fillStyle = 'var(--accent-yellow)';
  ctx.font = '11px -apple-system, sans-serif';
  ctx.textAlign = 'center';
  ctx.fillText('采样时序  Strain vs. Sample', padL + plotW / 2, H - 8);
}
