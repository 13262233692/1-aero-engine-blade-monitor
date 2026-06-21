import { useEffect, useRef, useState, useMemo } from 'react';

const ORDER_COLORS = [
  '#00d4ff',
  '#00ff9d',
  '#ffd700',
  '#ff8c00',
  '#ff3d57',
  '#b366ff',
  '#ff69b4',
  '#ff6347',
  '#7fff00',
  '#00bfff'
];

const ORDER_STROKE_COLORS = [
  'rgba(0, 212, 255, 0.25)',
  'rgba(0, 255, 157, 0.22)',
  'rgba(255, 215, 0, 0.20)',
  'rgba(255, 140, 0, 0.18)',
  'rgba(255, 61, 87, 0.16)',
  'rgba(179, 102, 255, 0.14)',
  'rgba(255, 105, 180, 0.13)',
  'rgba(255, 99, 71, 0.12)',
  'rgba(127, 255, 0, 0.11)',
  'rgba(0, 191, 255, 0.10)'
];

const BLADE_NATURAL_FREQS = [
  { freq: 210, label: '1st Bending (低阶弯曲)', color: '#00ff9d' },
  { freq: 520, label: '2nd Bending (二阶弯曲)', color: '#ffd700' },
  { freq: 890, label: '1st Torsion (一阶扭转)', color: '#ff8c00' },
  { freq: 1280, label: '3rd Bending (三阶弯曲)', color: '#ff3d57' },
  { freq: 1650, label: '2nd Torsion (二阶扭转)', color: '#b366ff' }
];

function formatNumber(n, decimals = 0) {
  if (n === 0) return '0';
  if (n >= 1e6) return (n / 1e6).toFixed(2) + 'M';
  if (n >= 1e3) return (n / 1e3).toFixed(1) + 'k';
  return n.toFixed(decimals);
}

export default function CampbellDiagram({ points, currentRpm, maxRpm = 20000, maxFreq = 2000 }) {
  const canvasRef = useRef(null);
  const containerRef = useRef(null);
  const animationRef = useRef(null);
  const dprRef = useRef(window.devicePixelRatio || 1);

  const renderStateRef = useRef({
    logicalW: 0,
    logicalH: 0,
    padLeft: 70,
    padRight: 30,
    padTop: 40,
    padBottom: 50,
    hoveredPoint: null,
    mouseX: -1,
    mouseY: -1
  });

  const [tooltip, setTooltip] = useState(null);

  const bladeCount = 24;

  const gridSpec = useMemo(() => ({
    rpmStep: maxRpm <= 10000 ? 1000 : maxRpm <= 20000 ? 2000 : 5000,
    freqStep: maxFreq <= 1000 ? 100 : maxFreq <= 2000 ? 200 : 500
  }), [maxRpm, maxFreq]);

  useEffect(() => {
    const canvas = canvasRef.current;
    const container = containerRef.current;
    if (!canvas || !container) return;

    const ctx = canvas.getContext('2d');
    const state = renderStateRef.current;

    function resize() {
      const rect = container.getBoundingClientRect();
      const dpr = window.devicePixelRatio || 1;
      dprRef.current = dpr;

      state.logicalW = rect.width;
      state.logicalH = rect.height;

      canvas.width = Math.floor(rect.width * dpr);
      canvas.height = Math.floor(rect.height * dpr);
      canvas.style.width = rect.width + 'px';
      canvas.style.height = rect.height + 'px';

      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    }

    resize();

    const ro = new ResizeObserver(resize);
    ro.observe(container);

    function render() {
      draw(ctx, state);
      animationRef.current = requestAnimationFrame(render);
    }

    animationRef.current = requestAnimationFrame(render);

    function onMouseMove(e) {
      const rect = canvas.getBoundingClientRect();
      const mx = e.clientX - rect.left;
      const my = e.clientY - rect.top;
      state.mouseX = mx;
      state.mouseY = my;

      const hit = findNearestPoint(state, points, mx, my);
      state.hoveredPoint = hit;

      if (hit && hit.point) {
        setTooltip({
          x: mx,
          y: my,
          data: hit.point
        });
      } else {
        setTooltip(null);
      }
    }

    function onMouseLeave() {
      state.mouseX = -1;
      state.mouseY = -1;
      state.hoveredPoint = null;
      setTooltip(null);
    }

    canvas.addEventListener('mousemove', onMouseMove);
    canvas.addEventListener('mouseleave', onMouseLeave);

    return () => {
      ro.disconnect();
      if (animationRef.current) cancelAnimationFrame(animationRef.current);
      canvas.removeEventListener('mousemove', onMouseMove);
      canvas.removeEventListener('mouseleave', onMouseLeave);
    };
  }, []);

  useEffect(() => {
    renderStateRef.current.points = points;
    renderStateRef.current.currentRpm = currentRpm;
    renderStateRef.current.maxRpm = maxRpm;
    renderStateRef.current.maxFreq = maxFreq;
    renderStateRef.current.gridSpec = gridSpec;
    renderStateRef.current.bladeCount = bladeCount;
  }, [points, currentRpm, maxRpm, maxFreq, gridSpec, bladeCount]);

  return (
    <div className="canvas-host" ref={containerRef}>
      <canvas ref={canvasRef} />
      <div className="canvas-overlay">
        <div className="legend">
          <div style={{ fontSize: '11px', color: 'var(--text-muted)', marginBottom: '4px', letterSpacing: '0.5px' }}>阶次线 / Orders</div>
          {[1, 2, 3, 4, 5].map(n => (
            <div className="legend-item" key={n}>
              <div className="legend-color" style={{ background: ORDER_COLORS[n - 1], color: ORDER_COLORS[n - 1] }} />
              <span>{n}X Order ({n === 1 ? '同步' : n + '阶谐波'})</span>
            </div>
          ))}
          <div style={{ height: '1px', background: 'var(--border-color)', margin: '6px 0' }} />
          <div style={{ fontSize: '11px', color: 'var(--text-muted)', marginBottom: '4px', letterSpacing: '0.5px' }}>叶片固有频率</div>
          {BLADE_NATURAL_FREQS.slice(0, 3).map((nf, i) => (
            <div className="legend-item" key={i}>
              <div className="legend-color" style={{ background: nf.color, color: nf.color }} />
              <span>{nf.freq} Hz — {nf.label.split(' ')[0]}</span>
            </div>
          ))}
        </div>

        {tooltip && tooltip.data && (
          <div className="info-tooltip" style={{ left: Math.min(tooltip.x + 14, 700), top: Math.max(tooltip.y - 80, 10) }}>
            <div><span>转速 RPM</span><span>{formatNumber(tooltip.data.rpm, 0)}</span></div>
            <div><span>振动频率</span><span>{tooltip.data.frequency.toFixed(1)} Hz</span></div>
            <div><span>振幅</span><span>{tooltip.data.amplitude.toFixed(2)} με</span></div>
            <div><span>阶次</span><span>{tooltip.data.order}X</span></div>
            <div><span>叶片#</span><span>{tooltip.data.bladeIndex}</span></div>
            <div><span>阶次频率</span><span>{(tooltip.data.rpm / 60 * tooltip.data.order).toFixed(1)} Hz</span></div>
          </div>
        )}
      </div>
    </div>
  );
}

function draw(ctx, state) {
  const W = state.logicalW;
  const H = state.logicalH;
  const { padLeft, padRight, padTop, padBottom } = state;
  const plotW = W - padLeft - padRight;
  const plotH = H - padTop - padBottom;

  ctx.clearRect(0, 0, W, H);

  const maxRpm = state.maxRpm || 20000;
  const maxFreq = state.maxFreq || 2000;
  const currentRpm = state.currentRpm || 0;
  const gridSpec = state.gridSpec || { rpmStep: 2000, freqStep: 200 };
  const bladeCount = state.bladeCount || 24;

  const rpmToX = (rpm) => padLeft + (rpm / maxRpm) * plotW;
  const freqToY = (freq) => padTop + plotH - (freq / maxFreq) * plotH;
  const xToRpm = (x) => ((x - padLeft) / plotW) * maxRpm;
  const yToFreq = (y) => ((padTop + plotH - y) / plotH) * maxFreq;

  drawPlotBackground(ctx, padLeft, padTop, plotW, plotH);
  drawGridLines(ctx, padLeft, padRight, padTop, padBottom, plotW, plotH,
    maxRpm, maxFreq, gridSpec, rpmToX, freqToY, currentRpm);

  drawNaturalFrequencyLines(ctx, padLeft, plotW, padTop, plotH, maxFreq, freqToY, W);

  drawOrderLines(ctx, padLeft, plotW, padTop, plotH, maxRpm, maxFreq, rpmToX, freqToY);

  drawBladePassingFreqLine(ctx, padLeft, plotW, padTop, plotH, maxRpm, maxFreq,
    bladeCount, rpmToX, freqToY);

  drawResonanceDangerZones(ctx, padLeft, plotW, padTop, plotH, maxRpm, maxFreq,
    rpmToX, freqToY);

  drawCampbellPoints(ctx, state.points || [], padLeft, plotW, padTop, plotH,
    maxRpm, maxFreq, rpmToX, freqToY);

  drawCurrentRpmIndicator(ctx, currentRpm, maxRpm, padLeft, padTop, plotW, plotH, rpmToX);

  drawAxes(ctx, W, H, padLeft, padRight, padTop, padBottom, plotW, plotH,
    maxRpm, maxFreq, gridSpec, rpmToX, freqToY);

  drawRpmFrequencyReadout(ctx, state, padLeft, padTop, plotW, plotH,
    maxRpm, maxFreq, xToRpm, yToFreq, currentRpm);
}

function drawPlotBackground(ctx, x, y, w, h) {
  const grad = ctx.createLinearGradient(x, y, x, y + h);
  grad.addColorStop(0, 'rgba(0, 212, 255, 0.03)');
  grad.addColorStop(0.5, 'rgba(13, 33, 55, 0.0)');
  grad.addColorStop(1, 'rgba(0, 255, 157, 0.02)');
  ctx.fillStyle = grad;
  ctx.fillRect(x, y, w, h);

  ctx.strokeStyle = 'rgba(30, 73, 118, 0.8)';
  ctx.lineWidth = 1;
  ctx.strokeRect(x + 0.5, y + 0.5, w - 1, h - 1);
}

function drawGridLines(ctx, padLeft, padRight, padTop, padBottom, plotW, plotH,
                       maxRpm, maxFreq, gridSpec, rpmToX, freqToY, currentRpm) {
  ctx.save();
  ctx.beginPath();
  ctx.rect(padLeft, padTop, plotW, plotH);
  ctx.clip();

  ctx.strokeStyle = 'rgba(30, 73, 118, 0.35)';
  ctx.lineWidth = 1;

  for (let rpm = 0; rpm <= maxRpm; rpm += gridSpec.rpmStep) {
    const x = Math.floor(rpmToX(rpm)) + 0.5;
    ctx.beginPath();
    ctx.moveTo(x, padTop);
    ctx.lineTo(x, padTop + plotH);
    const isMajor = rpm % (gridSpec.rpmStep * 2) === 0;
    ctx.strokeStyle = isMajor ? 'rgba(30, 73, 118, 0.55)' : 'rgba(30, 73, 118, 0.25)';
    ctx.lineWidth = isMajor ? 1 : 0.5;
    ctx.stroke();
  }

  for (let freq = 0; freq <= maxFreq; freq += gridSpec.freqStep) {
    const y = Math.floor(freqToY(freq)) + 0.5;
    ctx.beginPath();
    ctx.moveTo(padLeft, y);
    ctx.lineTo(padLeft + plotW, y);
    const isMajor = freq % (gridSpec.freqStep * 2) === 0;
    ctx.strokeStyle = isMajor ? 'rgba(30, 73, 118, 0.55)' : 'rgba(30, 73, 118, 0.25)';
    ctx.lineWidth = isMajor ? 1 : 0.5;
    ctx.stroke();
  }

  ctx.restore();
}

function drawNaturalFrequencyLines(ctx, padLeft, plotW, padTop, plotH, maxFreq, freqToY, W) {
  ctx.save();
  ctx.beginPath();
  ctx.rect(padLeft, padTop, plotW, plotH);
  ctx.clip();

  for (const nf of BLADE_NATURAL_FREQS) {
    if (nf.freq > maxFreq) continue;

    const y = freqToY(nf.freq);
    ctx.setLineDash([6, 4]);
    ctx.strokeStyle = nf.color + '60';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(padLeft, y);
    ctx.lineTo(padLeft + plotW, y);
    ctx.stroke();
    ctx.setLineDash([]);

    ctx.fillStyle = nf.color;
    ctx.globalAlpha = 0.85;
    ctx.font = '10px Consolas, monospace';
    ctx.textBaseline = 'bottom';
    ctx.textAlign = 'left';
    ctx.fillText(` fn${BLADE_NATURAL_FREQS.indexOf(nf) + 1} = ${nf.freq}Hz `, padLeft + 8, y - 3);
    ctx.globalAlpha = 1;
  }

  ctx.restore();
}

function drawOrderLines(ctx, padLeft, plotW, padTop, plotH, maxRpm, maxFreq, rpmToX, freqToY) {
  ctx.save();
  ctx.beginPath();
  ctx.rect(padLeft, padTop, plotW, plotH);
  ctx.clip();

  const y0 = freqToY(0);

  for (let order = 1; order <= 10; order++) {
    const freqAtMax = (maxRpm / 60) * order;

    let xEnd;
    if (freqAtMax <= maxFreq) {
      xEnd = rpmToX(maxRpm);
    } else {
      const rpmAtFreq = (maxFreq * 60) / order;
      xEnd = rpmToX(rpmAtFreq);
    }
    const yEnd = freqToY(Math.min(freqAtMax, maxFreq));

    ctx.beginPath();
    ctx.moveTo(rpmToX(0), y0);
    ctx.lineTo(xEnd, yEnd);

    const colorIdx = order - 1;
    ctx.strokeStyle = ORDER_STROKE_COLORS[colorIdx] || 'rgba(255,255,255,0.1)';
    ctx.lineWidth = order <= 5 ? 2 : 1;
    ctx.stroke();

    if (order <= 5) {
      const labelRpm = Math.min(maxRpm * 0.88, (maxFreq * 60 / order) * 0.92);
      if (labelRpm > 0 && labelRpm < maxRpm) {
        const lx = rpmToX(labelRpm);
        const ly = freqToY((labelRpm / 60) * order);
        if (ly > padTop + 15 && ly < padTop + plotH - 8 && lx < padLeft + plotW - 50) {
          ctx.save();
          ctx.translate(lx, ly);
          const angle = -Math.atan((freqToY(0) - freqToY(60 * order)) / (rpmToX(60 * 60) - rpmToX(0)));
          ctx.rotate(angle);
          ctx.fillStyle = ORDER_COLORS[colorIdx] + 'aa';
          ctx.font = 'bold 11px Consolas, monospace';
          ctx.textAlign = 'center';
          ctx.textBaseline = 'bottom';
          ctx.fillText(`${order}X`, 0, -6);
          ctx.restore();
        }
      }
    }
  }

  ctx.restore();
}

function drawBladePassingFreqLine(ctx, padLeft, plotW, padTop, plotH, maxRpm, maxFreq,
                                   bladeCount, rpmToX, freqToY) {
  ctx.save();
  ctx.beginPath();
  ctx.rect(padLeft, padTop, plotW, plotH);
  ctx.clip();

  const order = bladeCount;
  const freqAtMax = (maxRpm / 60) * order;

  let xEnd;
  if (freqAtMax <= maxFreq) {
    xEnd = rpmToX(maxRpm);
  } else {
    const rpmAtFreq = (maxFreq * 60) / order;
    xEnd = rpmToX(rpmAtFreq);
  }
  const yEnd = freqToY(Math.min(freqAtMax, maxFreq));

  const y0 = freqToY(0);
  ctx.setLineDash([4, 6]);
  ctx.beginPath();
  ctx.moveTo(rpmToX(0), y0);
  ctx.lineTo(xEnd, yEnd);
  ctx.strokeStyle = 'rgba(179, 102, 255, 0.35)';
  ctx.lineWidth = 2;
  ctx.stroke();
  ctx.setLineDash([]);

  const labelRpm = Math.min(maxRpm * 0.6, (maxFreq * 60 / order) * 0.85);
  if (labelRpm > 0) {
    const lx = rpmToX(labelRpm);
    const ly = freqToY((labelRpm / 60) * order);
    if (ly > padTop + 20 && ly < padTop + plotH - 20) {
      ctx.save();
      ctx.translate(lx, ly);
      const angle = -Math.atan((freqToY(0) - freqToY(60 * order)) / (rpmToX(60 * 60) - rpmToX(0)));
      ctx.rotate(angle);
      ctx.fillStyle = 'rgba(179, 102, 255, 0.8)';
      ctx.font = 'bold 10px Consolas, monospace';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'bottom';
      ctx.fillText(`BPF (${bladeCount}X)`, 0, -8);
      ctx.restore();
    }
  }

  ctx.restore();
}

function drawResonanceDangerZones(ctx, padLeft, plotW, padTop, plotH, maxRpm, maxFreq,
                                   rpmToX, freqToY) {
  if (plotW <= 0 || plotH <= 0) return;

  ctx.save();
  ctx.beginPath();
  ctx.rect(padLeft, padTop, plotW, plotH);
  ctx.clip();

  for (let order = 1; order <= 5; order++) {
    for (const nf of BLADE_NATURAL_FREQS) {
      const resonanceRpm = (nf.freq * 60) / order;
      if (resonanceRpm <= 0 || resonanceRpm > maxRpm) continue;

      const x0 = rpmToX(resonanceRpm);
      const y0 = freqToY(nf.freq);
      const halfWidth = Math.max(1, plotW * 0.025);
      const outerRadius = Math.max(1, halfWidth * 2);

      const zoneGrad = ctx.createRadialGradient(x0, y0, 0, x0, y0, outerRadius);
      zoneGrad.addColorStop(0, 'rgba(255, 61, 87, 0.25)');
      zoneGrad.addColorStop(0.5, 'rgba(255, 61, 87, 0.08)');
      zoneGrad.addColorStop(1, 'rgba(255, 61, 87, 0)');
      ctx.fillStyle = zoneGrad;
      ctx.beginPath();
      ctx.arc(x0, y0, halfWidth * 2, 0, Math.PI * 2);
      ctx.fill();

      ctx.strokeStyle = 'rgba(255, 61, 87, 0.6)';
      ctx.lineWidth = 1;
      ctx.setLineDash([2, 2]);
      ctx.beginPath();
      ctx.arc(x0, y0, halfWidth, 0, Math.PI * 2);
      ctx.stroke();
      ctx.setLineDash([]);

      ctx.fillStyle = 'rgba(255, 61, 87, 0.85)';
      ctx.beginPath();
      ctx.arc(x0, y0, 3.5, 0, Math.PI * 2);
      ctx.fill();
    }
  }

  ctx.restore();
}

function drawCampbellPoints(ctx, points, padLeft, plotW, padTop, plotH,
                            maxRpm, maxFreq, rpmToX, freqToY) {
  if (!points || points.length === 0) return;

  ctx.save();
  ctx.beginPath();
  ctx.rect(padLeft, padTop, plotW, plotH);
  ctx.clip();

  const len = points.length;
  const fadeStart = Math.max(0, len - 5000);

  for (let i = fadeStart; i < len; i++) {
    const p = points[i];
    if (!p || p.rpm < 0 || p.rpm > maxRpm || p.frequency < 0 || p.frequency > maxFreq) continue;

    const x = rpmToX(p.rpm);
    const y = freqToY(p.frequency);

    const age = len - i;
    const alpha = age < 3000
      ? 1 - age / 5000
      : Math.max(0.08, 1 - age / 6000);

    const colorIdx = Math.max(0, Math.min(ORDER_COLORS.length - 1, (p.order || 1) - 1));
    const color = ORDER_COLORS[colorIdx];
    const ampNorm = Math.min(1, (p.amplitude || 10) / 200);
    const size = 1.2 + ampNorm * 3.0;

    ctx.globalAlpha = alpha * (0.4 + ampNorm * 0.5);
    ctx.fillStyle = color;
    ctx.shadowColor = color;
    ctx.shadowBlur = size * 1.5;
    ctx.beginPath();
    ctx.arc(x, y, size, 0, Math.PI * 2);
    ctx.fill();
  }

  ctx.shadowBlur = 0;
  ctx.globalAlpha = 1;

  if (len > 0) {
    const recentStart = Math.max(0, len - 200);
    for (let i = len - 1; i >= recentStart; i--) {
      const p = points[i];
      if (!p || p.rpm < 0 || p.rpm > maxRpm || p.frequency < 0 || p.frequency > maxFreq) continue;

      const x = rpmToX(p.rpm);
      const y = freqToY(p.frequency);
      const colorIdx = Math.max(0, Math.min(ORDER_COLORS.length - 1, (p.order || 1) - 1));
      const color = ORDER_COLORS[colorIdx];
      const age = len - i;
      const alpha = Math.max(0.4, 1 - age / 200);
      const size = 2.2 + Math.min(2, (p.amplitude || 0) / 80);

      ctx.globalAlpha = alpha;
      ctx.strokeStyle = '#fff';
      ctx.lineWidth = 0.8;
      ctx.fillStyle = color;
      ctx.beginPath();
      ctx.arc(x, y, size + 0.5, 0, Math.PI * 2);
      ctx.stroke();
      ctx.beginPath();
      ctx.arc(x, y, size, 0, Math.PI * 2);
      ctx.fill();
    }
  }

  ctx.globalAlpha = 1;
  ctx.restore();
}

function drawCurrentRpmIndicator(ctx, currentRpm, maxRpm, padLeft, padTop, plotW, plotH, rpmToX) {
  if (!currentRpm || currentRpm <= 0 || currentRpm > maxRpm) return;

  ctx.save();
  ctx.beginPath();
  ctx.rect(padLeft, padTop, plotW, plotH);
  ctx.clip();

  const x = rpmToX(currentRpm);
  const grad = ctx.createLinearGradient(x, padTop, x, padTop + plotH);
  grad.addColorStop(0, 'rgba(0, 255, 157, 0.0)');
  grad.addColorStop(0.5, 'rgba(0, 255, 157, 0.12)');
  grad.addColorStop(1, 'rgba(0, 255, 157, 0.0)');
  ctx.fillStyle = grad;
  ctx.fillRect(x - plotW * 0.006, padTop, plotW * 0.012, plotH);

  ctx.strokeStyle = 'rgba(0, 255, 157, 0.9)';
  ctx.lineWidth = 2;
  ctx.setLineDash([6, 6]);
  ctx.beginPath();
  ctx.moveTo(x, padTop);
  ctx.lineTo(x, padTop + plotH);
  ctx.stroke();
  ctx.setLineDash([]);

  ctx.fillStyle = 'rgba(0, 255, 157, 0.95)';
  ctx.font = 'bold 11px Consolas, monospace';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'top';
  const rpmText = formatNumber(currentRpm, 0) + ' RPM';
  const textW = ctx.measureText(rpmText).width;
  ctx.fillRect(x - textW / 2 - 8, padTop + 4, textW + 16, 18);
  ctx.fillStyle = 'rgba(10, 25, 41, 1)';
  ctx.fillText(rpmText, x, padTop + 8);

  ctx.restore();
}

function drawAxes(ctx, W, H, padLeft, padRight, padTop, padBottom, plotW, plotH,
                  maxRpm, maxFreq, gridSpec, rpmToX, freqToY) {
  ctx.fillStyle = 'var(--text-muted)';
  ctx.font = '11px Consolas, monospace';
  ctx.strokeStyle = 'rgba(141, 185, 227, 0.6)';
  ctx.lineWidth = 1.2;

  ctx.beginPath();
  ctx.moveTo(padLeft, padTop);
  ctx.lineTo(padLeft, padTop + plotH);
  ctx.lineTo(padLeft + plotW, padTop + plotH);
  ctx.stroke();

  ctx.textAlign = 'center';
  ctx.textBaseline = 'top';
  for (let rpm = 0; rpm <= maxRpm; rpm += gridSpec.rpmStep) {
    const x = rpmToX(rpm);
    ctx.fillStyle = 'rgba(141, 185, 227, 0.5)';
    ctx.fillRect(x - 0.5, padTop + plotH, 1, 5);
    ctx.fillStyle = 'var(--text-secondary)';
    ctx.fillText(formatNumber(rpm, 0), x, padTop + plotH + 8);
  }

  ctx.textAlign = 'right';
  ctx.textBaseline = 'middle';
  for (let freq = 0; freq <= maxFreq; freq += gridSpec.freqStep) {
    const y = freqToY(freq);
    ctx.fillStyle = 'rgba(141, 185, 227, 0.5)';
    ctx.fillRect(padLeft - 5, y - 0.5, 5, 1);
    ctx.fillStyle = 'var(--text-secondary)';
    ctx.fillText(freq.toFixed(0), padLeft - 10, y);
  }

  ctx.save();
  ctx.translate(18, padTop + plotH / 2);
  ctx.rotate(-Math.PI / 2);
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillStyle = 'var(--accent-cyan)';
  ctx.font = 'bold 12px -apple-system, system-ui, sans-serif';
  ctx.letterSpacing = '1px';
  ctx.fillText('振动频率  Frequency (Hz)', 0, 0);
  ctx.restore();

  ctx.textAlign = 'center';
  ctx.textBaseline = 'top';
  ctx.fillStyle = 'var(--accent-green)';
  ctx.font = 'bold 12px -apple-system, system-ui, sans-serif';
  ctx.fillText('主轴转速  Spindle Speed (RPM)', padLeft + plotW / 2, H - 18);

  ctx.strokeStyle = 'rgba(30, 73, 118, 0.5)';
  ctx.lineWidth = 1;
  ctx.strokeRect(padLeft + 0.5, padTop + 0.5, plotW - 1, plotH - 1);
}

function drawRpmFrequencyReadout(ctx, state, padLeft, padTop, plotW, plotH,
                                  maxRpm, maxFreq, xToRpm, yToFreq, currentRpm) {
  const mx = state.mouseX;
  const my = state.mouseY;

  if (mx < padLeft || mx > padLeft + plotW || my < padTop || my > padTop + plotH) {
    return;
  }

  const rpm = xToRpm(mx);
  const freq = yToFreq(my);

  ctx.save();
  ctx.strokeStyle = 'rgba(0, 212, 255, 0.4)';
  ctx.lineWidth = 1;
  ctx.setLineDash([3, 3]);
  ctx.beginPath();
  ctx.moveTo(mx, padTop);
  ctx.lineTo(mx, padTop + plotH);
  ctx.moveTo(padLeft, my);
  ctx.lineTo(padLeft + plotW, my);
  ctx.stroke();
  ctx.setLineDash([]);

  const rpmBoxW = 95;
  ctx.fillStyle = 'rgba(10, 25, 41, 0.9)';
  ctx.strokeStyle = 'rgba(0, 212, 255, 0.6)';
  ctx.lineWidth = 1;
  const boxX = Math.min(padLeft + plotW - rpmBoxW - 4, Math.max(padLeft + 4, mx + 10));
  ctx.beginPath();
  ctx.roundRect(boxX, padTop + 8, rpmBoxW, 22, 4);
  ctx.fill();
  ctx.stroke();

  ctx.fillStyle = 'var(--accent-cyan)';
  ctx.font = 'bold 11px Consolas, monospace';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(formatNumber(rpm, 0) + ' RPM', boxX + rpmBoxW / 2, padTop + 19);

  const freqBoxW = 95;
  ctx.fillStyle = 'rgba(10, 25, 41, 0.9)';
  ctx.strokeStyle = 'rgba(0, 255, 157, 0.6)';
  const boxY = Math.min(padTop + plotH - 22 - 4, Math.max(padTop + 4, my + 10));
  ctx.beginPath();
  ctx.roundRect(padLeft + 8, boxY, freqBoxW, 22, 4);
  ctx.fill();
  ctx.stroke();

  ctx.fillStyle = 'var(--accent-green)';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(freq.toFixed(1) + ' Hz', padLeft + 8 + freqBoxW / 2, boxY + 11);

  const ordersBoxH = 155;
  const ordersBoxW = 130;
  const obx = padLeft + plotW - ordersBoxW - 12;
  const oby = padTop + 38;

  ctx.fillStyle = 'rgba(10, 25, 41, 0.88)';
  ctx.strokeStyle = 'rgba(30, 73, 118, 0.8)';
  ctx.beginPath();
  ctx.roundRect(obx, oby, ordersBoxW, ordersBoxH, 6);
  ctx.fill();
  ctx.stroke();

  ctx.fillStyle = 'var(--text-muted)';
  ctx.font = '10px Consolas, monospace';
  ctx.textAlign = 'left';
  ctx.textBaseline = 'top';
  ctx.fillText('— 阶次频率表 @光标 —', obx + 10, oby + 8);

  const rowY0 = oby + 28;
  const rowH = 20;
  for (let order = 1; order <= 6; order++) {
    const of = (rpm / 60) * order;
    const ry = rowY0 + (order - 1) * rowH;
    const colorIdx = order - 1;

    ctx.fillStyle = ORDER_COLORS[colorIdx] || '#fff';
    ctx.beginPath();
    ctx.arc(obx + 18, ry + rowH / 2, 4, 0, Math.PI * 2);
    ctx.fill();

    ctx.fillStyle = 'var(--text-secondary)';
    ctx.font = '10px Consolas, monospace';
    ctx.textAlign = 'left';
    ctx.textBaseline = 'middle';
    ctx.fillText(`${order}X`, obx + 30, ry + rowH / 2);

    ctx.fillStyle = 'var(--text-primary)';
    ctx.textAlign = 'right';
    ctx.font = 'bold 10px Consolas, monospace';
    ctx.fillText(of.toFixed(1) + ' Hz', obx + ordersBoxW - 10, ry + rowH / 2);

    if (of > maxFreq) {
      ctx.fillStyle = 'rgba(255, 61, 87, 0.7)';
      ctx.fillText('↑', obx + ordersBoxW - 10, ry + rowH / 2);
    }
  }

  ctx.restore();
}

function findNearestPoint(state, points, mx, my) {
  if (!points || points.length === 0) return null;

  const W = state.logicalW;
  const H = state.logicalH;
  const { padLeft, padRight, padTop, padBottom } = state;
  const plotW = W - padLeft - padRight;
  const plotH = H - padTop - padBottom;
  const maxRpm = state.maxRpm || 20000;
  const maxFreq = state.maxFreq || 2000;

  const rpmToX = (rpm) => padLeft + (rpm / maxRpm) * plotW;
  const freqToY = (freq) => padTop + plotH - (freq / maxFreq) * plotH;

  let nearest = null;
  let minDist = 12;

  const searchStart = Math.max(0, points.length - 2000);
  for (let i = points.length - 1; i >= searchStart; i--) {
    const p = points[i];
    if (!p) continue;
    const px = rpmToX(p.rpm);
    const py = freqToY(p.frequency);
    const dx = mx - px;
    const dy = my - py;
    const dist = Math.sqrt(dx * dx + dy * dy);
    if (dist < minDist) {
      minDist = dist;
      nearest = { point: p, dist, screenX: px, screenY: py };
    }
  }

  return nearest;
}
