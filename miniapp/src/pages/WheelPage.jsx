import { useEffect, useRef, useState, useCallback } from 'react';
import { getWheelStatus, spinWheel } from '../api/client';
import BorderBeamCard from '../components/BorderBeamCard';
import BackButton from '../components/BackButton';
import './WheelPage.css';

const SECTORS = [
  { label: '50 EXC',       color: '#4c1d95', textColor: '#ddd6fe' },
  { label: '100 EXC',      color: '#5b21b6', textColor: '#ede9fe' },
  { label: '300 EXC',      color: '#7c3aed', textColor: '#f5f3ff' },
  { label: '500 EXC',      color: '#9333ea', textColor: '#faf5ff' },
  { label: '1 000 EXC',    color: '#a21caf', textColor: '#fdf4ff' },
  { label: '2 000 EXC',    color: '#be185d', textColor: '#fce7f3' },
  { label: 'XP-буст 24ч', color: '#0e7490', textColor: '#e0f2fe' },
  { label: 'Рамка 👑',     color: '#92400e', textColor: '#fef3c7' },
];

const N = SECTORS.length;
const SLICE = (2 * Math.PI) / N;

function drawWheel(canvas, rotation) {
  const ctx = canvas.getContext('2d');
  const size = canvas.width;
  const cx = size / 2;
  const cy = size / 2;
  const r = size / 2 - 4;

  ctx.clearRect(0, 0, size, size);

  SECTORS.forEach((sec, i) => {
    const start = rotation + i * SLICE;
    const end = start + SLICE;

    ctx.beginPath();
    ctx.moveTo(cx, cy);
    ctx.arc(cx, cy, r, start, end);
    ctx.closePath();
    ctx.fillStyle = sec.color;
    ctx.fill();
    ctx.strokeStyle = 'rgba(0,0,0,0.5)';
    ctx.lineWidth = 1.5;
    ctx.stroke();

    ctx.save();
    ctx.translate(cx, cy);
    ctx.rotate(start + SLICE / 2);
    ctx.textAlign = 'right';
    ctx.fillStyle = sec.textColor;
    ctx.font = `bold ${size < 280 ? 9 : 11}px sans-serif`;
    ctx.fillText(sec.label, r - 8, 4);
    ctx.restore();
  });

  // Glow ring
  ctx.beginPath();
  ctx.arc(cx, cy, r, 0, 2 * Math.PI);
  ctx.strokeStyle = 'rgba(168,85,247,0.5)';
  ctx.lineWidth = 3;
  ctx.stroke();

  // Center cap
  const cap = ctx.createRadialGradient(cx, cy, 0, cx, cy, 24);
  cap.addColorStop(0, '#1a0a2e');
  cap.addColorStop(1, '#0d0617');
  ctx.beginPath();
  ctx.arc(cx, cy, 24, 0, 2 * Math.PI);
  ctx.fillStyle = cap;
  ctx.fill();
  ctx.strokeStyle = 'rgba(168,85,247,0.7)';
  ctx.lineWidth = 2;
  ctx.stroke();

  ctx.font = '18px serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText('🎰', cx, cy);
}

const PRIZES = [
  { label: '🥉 50 EXC',       prob: '30%' },
  { label: '🥉 100 EXC',      prob: '25%' },
  { label: '🥈 300 EXC',      prob: '20%' },
  { label: '🥈 500 EXC',      prob: '12%' },
  { label: '🥇 1 000 EXC',    prob: '8%'  },
  { label: '🥇 2 000 EXC',    prob: '3%'  },
  { label: '💎 XP-буст 24ч',  prob: '1.5%'},
  { label: '👑 Рамка аватара', prob: '0.5%'},
];

export default function WheelPage() {
  const canvasRef = useRef(null);
  const rotRef = useRef(0);
  const rafRef = useRef(null);

  const [status, setStatus] = useState(null);
  const [spinning, setSpinning] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState('');

  const redraw = useCallback(() => {
    if (canvasRef.current) drawWheel(canvasRef.current, rotRef.current);
  }, []);

  useEffect(() => {
    redraw();
    getWheelStatus().then(setStatus).catch(() => {});
  }, [redraw]);

  function sectorIndexForResult(res) {
    if (res.type === 'BOOST_24H') return 6;
    if (res.type === 'AVATAR_FRAME') return 7;
    const excMap = { 50: 0, 100: 1, 300: 2, 500: 3, 1000: 4, 2000: 5 };
    return excMap[res.excAmount] ?? 0;
  }

  function targetRotForSector(fromRot, sectorIdx, minRevs = 5) {
    // Arrow is at top = 270° = 3π/2. We want the center of sectorIdx under it.
    // Sector i center angle = sectorIdx * SLICE + SLICE/2 (from rotation origin)
    // We need: finalRot + sectorIdx * SLICE + SLICE/2 ≡ 3π/2 (mod 2π)
    const arrow = Math.PI * 3 / 2;
    let target = arrow - (sectorIdx * SLICE + SLICE / 2);
    // Normalize to [0, 2π)
    target = ((target % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
    // Add enough full rotations so we spin at least minRevs from current position
    const minTarget = fromRot + minRevs * 2 * Math.PI;
    while (target < minTarget) target += 2 * Math.PI;
    return target;
  }

  async function handleSpin() {
    if (spinning) return;
    setResult(null);
    setError('');
    setSpinning(true);

    function easeOut(t) { return 1 - Math.pow(1 - t, 4); }

    // Phase 1: fast free spin while waiting for API (2s)
    const phase1Duration = 2000;
    const phase1Speed = 4 * 2 * Math.PI; // rad/s
    const phase1StartRot = rotRef.current;
    const phase1StartTime = performance.now();

    const [res] = await Promise.all([
      spinWheel().catch(e => ({ success: false, message: String(e) })),
      new Promise(resolve => {
        function frame(now) {
          const elapsed = now - phase1StartTime;
          if (elapsed < phase1Duration) {
            rotRef.current = phase1StartRot + phase1Speed * (elapsed / 1000);
            redraw();
            rafRef.current = requestAnimationFrame(frame);
          } else {
            rotRef.current = phase1StartRot + phase1Speed * (phase1Duration / 1000);
            redraw();
            resolve();
          }
        }
        rafRef.current = requestAnimationFrame(frame);
      }),
    ]);

    cancelAnimationFrame(rafRef.current);

    // Phase 2: decelerate to the correct sector (2s)
    const phase2StartRot = rotRef.current;
    let finalTarget;
    if (res.success) {
      finalTarget = targetRotForSector(phase2StartRot, sectorIndexForResult(res), 2);
    } else {
      // On error just do 2 more full spins and stop
      finalTarget = phase2StartRot + 2 * 2 * Math.PI;
    }

    const phase2Duration = 2000;
    const phase2StartTime = performance.now();

    await new Promise(resolve => {
      function frame(now) {
        const t = Math.min((now - phase2StartTime) / phase2Duration, 1);
        rotRef.current = phase2StartRot + (finalTarget - phase2StartRot) * easeOut(t);
        redraw();
        if (t < 1) rafRef.current = requestAnimationFrame(frame);
        else { rotRef.current = finalTarget; redraw(); resolve(); }
      }
      rafRef.current = requestAnimationFrame(frame);
    });

    setSpinning(false);

    if (res.success) {
      setResult(res);
      setStatus(s => s ? { ...s, tickets: res.newTickets, spinsToday: res.spinsToday } : s);
    } else {
      setError(res.message || 'Ошибка');
      getWheelStatus().then(setStatus).catch(() => {});
    }
  }

  const canSpin = !spinning && status && status.tickets > 0 && status.spinsToday < 10;

  return (
    <div className="wheel-page">
      {/* Header */}
      <div className="wheel-header">
        <BackButton label="Назад" />
        <h1>🎰 Колесо фортуны</h1>
        <div className="wheel-chips">
          <div className="wheel-chip">
            <span>🎟</span>
            <span>Билеты: <strong>{status ? status.tickets : '…'}</strong></span>
          </div>
          <div className="wheel-chip">
            <span>🔄</span>
            <span>Сегодня: <strong>{status ? `${status.spinsToday}/10` : '…'}</strong></span>
          </div>
        </div>
      </div>

      {/* Wheel */}
      <div className="wheel-canvas-wrap">
        <div className="wheel-arrow">▼</div>
        <canvas ref={canvasRef} width={280} height={280} className="wheel-canvas" />
      </div>

      {/* Spin button */}
      <button
        className={`wheel-btn${canSpin ? ' wheel-btn--active' : ''}`}
        onClick={handleSpin}
        disabled={!canSpin}
      >
        {spinning
          ? '⏳ Крутится...'
          : canSpin
            ? '🎰 Крутить  −1 🎟'
            : status?.tickets === 0 ? '🎟 Нет билетов' : '🚫 Лимит исчерпан'}
      </button>

      {/* Result */}
      {result && (
        <BorderBeamCard className="wheel-result">
          <div className="wheel-result-prize">🎊 {result.label}</div>
          {result.type === 'EXC' && (
            <div className="wheel-result-sub" style={{ color: '#fbbf24' }}>
              +{result.excAmount.toLocaleString('ru')} EXC зачислено
            </div>
          )}
          {result.type === 'BOOST_24H' && (
            <div className="wheel-result-sub" style={{ color: '#38bdf8' }}>
              ⚡ XP-буст активирован на 24 часа
            </div>
          )}
          {result.type === 'AVATAR_FRAME' && (
            <div className="wheel-result-sub" style={{ color: '#fbbf24' }}>
              ✨ Рамка применена к профилю
            </div>
          )}
        </BorderBeamCard>
      )}

      {/* Error */}
      {error && (
        <div className="wheel-error">⚠️ {error}</div>
      )}

      {/* How to get tickets */}
      <div className="wheel-section">
        <div className="wheel-section-title">Как получить билеты 🎟</div>
        <div className="wheel-tickets-grid">
          {[
            { icon: '🗺️', title: 'Лёгкий квест', sub: '+1 билет' },
            { icon: '⚔️', title: 'Средний квест', sub: '+2 билета' },
            { icon: '🏆', title: 'Сложный квест', sub: '+3 билета' },
            { icon: '🔥', title: '3 дня подряд',  sub: '+1 билет' },
            { icon: '💥', title: '7 дней подряд', sub: '+2 билета' },
            { icon: '⭐', title: '14 дней подряд', sub: '+3 билета' },
          ].map((item, i) => (
            <div key={i} className="wheel-ticket-card">
              <span className="wheel-ticket-icon">{item.icon}</span>
              <div>
                <div className="wheel-ticket-title">{item.title}</div>
                <div className="wheel-ticket-sub">{item.sub}</div>
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Prize table */}
      <div className="wheel-section">
        <div className="wheel-section-title">Таблица призов</div>
        <BorderBeamCard>
          <table className="wheel-prize-table">
            <tbody>
              {PRIZES.map((p, i) => (
                <tr key={i}>
                  <td>{p.label}</td>
                  <td className="wheel-prize-prob">{p.prob}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </BorderBeamCard>
        <div className="wheel-note">Лимит: 10 кручений в сутки · 1 билет = 1 кручение</div>
      </div>
    </div>
  );
}
