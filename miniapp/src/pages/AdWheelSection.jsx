import { useEffect, useRef, useState, useCallback } from 'react';
import { getAdWheelStatus, spinAdWheel, requestAdWatch, invalidateCache } from '../api/client';
import BorderBeamCard from '../components/BorderBeamCard';
import BackButton from '../components/BackButton';
import { useParticles } from '../components/ParticlesContext';
import { useAdsgram } from '../hooks/useAdsgram';
import { useTelegaAds } from '../hooks/useTelegaAds';
import './AdWheelSection.css';

// Отдельный AdsGram-блок под колесо — чтобы CPM колеса считался отдельно от карточки «Посмотри рекламу — получи EXC».
// Пока VITE_ADSGRAM_WHEEL_BLOCK_ID не задан, используется общий блок. Reward URL у нового блока в кабинете AdsGram —
// тот же, что у общего (сервер различает «спин» и «30 EXC» по цели, выставленной в /api/ads/watch, а не по блоку).
const ADSGRAM_WHEEL_BLOCK_ID = import.meta.env.VITE_ADSGRAM_WHEEL_BLOCK_ID;
const ADSGRAM_GENERAL_BLOCK_ID = import.meta.env.VITE_ADSGRAM_BLOCK_ID;
// Порядок важен: сначала блок колеса (своя статистика CPM), при ошибке/no-fill (в т.ч. пока блок на модерации) useAdsgram
// пробует следующий - общий блок, чтобы игрок не остался без ролика.
const ADSGRAM_BLOCK_IDS = [...new Set([ADSGRAM_WHEEL_BLOCK_ID, ADSGRAM_GENERAL_BLOCK_ID].filter(Boolean))];
const TELEGA_AD_BLOCK_UUID = import.meta.env.VITE_TELEGA_AD_BLOCK_UUID;

// Секторы и шансы должны совпадать с AdWheelService на бэкенде (там веса из 100 000). Порядок на колесе —
// «чередуем дешёвое и дорогое», чтобы соседние сектора не сливались; сервер присылает приз (type + excAmount),
// клиент только находит сектор для остановки.
const SECTORS = [
  { key: 'EXC:10',   top: '10',  sub: 'EXC',   tone: 'a' },
  { key: 'EXC:100',  top: '100', sub: 'EXC',   tone: 'b' },
  { key: 'EXC:20',   top: '20',  sub: 'EXC',   tone: 'a' },
  { key: 'TICKET',   top: '🎟',  sub: 'Билет', tone: 'ticket' },
  { key: 'EXC:30',   top: '30',  sub: 'EXC',   tone: 'a' },
  { key: 'EXC:200',  top: '200', sub: 'EXC',   tone: 'b' },
  { key: 'EXC:50',   top: '50',  sub: 'EXC',   tone: 'a' },
  { key: 'EXC:500',  top: '500', sub: 'EXC',   tone: 'b' },
  { key: 'EXC:5000', top: '5000', sub: 'Джекпот', tone: 'jackpot' },
];

const PRIZES = [
  { label: '10 EXC',  prob: '32.98%' },
  { label: '20 EXC',  prob: '28%' },
  { label: '30 EXC',  prob: '18%' },
  { label: '50 EXC',  prob: '10%' },
  { label: '100 EXC', prob: '5.5%' },
  { label: '200 EXC', prob: '2.5%' },
  { label: '🎟 Билет обычного колеса', prob: '2.5%' },
  { label: '500 EXC', prob: '0.5%' },
  { label: '👑 5 000 EXC — джекпот', prob: '0.02% (1 из 5 000)' },
];

const N = SECTORS.length;
const SLICE = (2 * Math.PI) / N;
const LOGICAL = 300; // размер холста в CSS-пикселях, реальное разрешение умножается на devicePixelRatio

const TONES = {
  a:       { inner: '#171329', outer: '#2a2148', text: '#ffe7a3' },
  b:       { inner: '#241703', outer: '#4d3410', text: '#ffe7a3' },
  ticket:  { inner: '#2c1257', outer: '#6b2fd1', text: '#ffffff' },
  jackpot: { inner: '#4a0812', outer: '#b3122b', text: '#ffe7a3' },
};

function drawAdWheel(canvas, rotation) {
  const ctx = canvas.getContext('2d');
  const dpr = canvas.width / LOGICAL;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, LOGICAL, LOGICAL);

  const cx = LOGICAL / 2;
  const cy = LOGICAL / 2;
  const R = LOGICAL / 2 - 3;   // внешний край золотого обода
  const RIM = 17;              // толщина обода
  const r = R - RIM;           // радиус секторов

  // Золотой обод (металлический градиент)
  const rim = ctx.createLinearGradient(0, 0, LOGICAL, LOGICAL);
  rim.addColorStop(0, '#fff3b8');
  rim.addColorStop(0.25, '#e0a91b');
  rim.addColorStop(0.5, '#7a5200');
  rim.addColorStop(0.75, '#f7d774');
  rim.addColorStop(1, '#a87400');
  ctx.beginPath();
  ctx.arc(cx, cy, R, 0, 2 * Math.PI);
  ctx.fillStyle = rim;
  ctx.fill();
  ctx.beginPath();
  ctx.arc(cx, cy, r + 2, 0, 2 * Math.PI);
  ctx.fillStyle = '#07060d';
  ctx.fill();

  // Сектора
  SECTORS.forEach((sec, i) => {
    const start = rotation + i * SLICE;
    const end = start + SLICE;
    const tone = TONES[sec.tone];

    const g = ctx.createRadialGradient(cx, cy, r * 0.12, cx, cy, r);
    g.addColorStop(0, tone.inner);
    g.addColorStop(1, tone.outer);
    ctx.beginPath();
    ctx.moveTo(cx, cy);
    ctx.arc(cx, cy, r, start, end);
    ctx.closePath();
    ctx.fillStyle = g;
    ctx.fill();
    ctx.strokeStyle = 'rgba(245, 204, 100, 0.55)';
    ctx.lineWidth = 1.2;
    ctx.stroke();

    ctx.save();
    ctx.translate(cx, cy);
    ctx.rotate(start + SLICE / 2);
    ctx.textAlign = 'right';
    ctx.textBaseline = 'middle';
    ctx.fillStyle = tone.text;
    ctx.shadowColor = 'rgba(0,0,0,0.6)';
    ctx.shadowBlur = 4;
    ctx.font = `800 ${sec.top.length > 3 ? 17 : sec.top.length > 2 ? 19 : 22}px sans-serif`;
    ctx.fillText(sec.top, r - 16, -5);
    ctx.font = '700 9px sans-serif';
    ctx.globalAlpha = 0.8;
    ctx.fillText(sec.sub.toUpperCase(), r - 16, 12);
    ctx.restore();
  });

  // Внутренняя золотая окантовка секторов
  ctx.beginPath();
  ctx.arc(cx, cy, r, 0, 2 * Math.PI);
  ctx.strokeStyle = 'rgba(255, 215, 120, 0.9)';
  ctx.lineWidth = 2;
  ctx.stroke();

  // «Лампочки» на ободе — мигают попеременно, пока крутится (фаза по времени)
  const phase = Math.floor(performance.now() / 180) % 2;
  const studs = 24;
  for (let i = 0; i < studs; i++) {
    const a = (i / studs) * 2 * Math.PI;
    const sx = cx + Math.cos(a) * (R - RIM / 2 - 0.5);
    const sy = cy + Math.sin(a) * (R - RIM / 2 - 0.5);
    const lit = (i + phase) % 2 === 0;
    ctx.beginPath();
    ctx.arc(sx, sy, 2.6, 0, 2 * Math.PI);
    ctx.fillStyle = lit ? '#fffbe0' : '#8a6410';
    if (lit) {
      ctx.shadowColor = '#ffd84d';
      ctx.shadowBlur = 8;
    }
    ctx.fill();
    ctx.shadowBlur = 0;
  }

  // Центральная золотая «кнопка»
  const cap = ctx.createRadialGradient(cx - 6, cy - 6, 2, cx, cy, 30);
  cap.addColorStop(0, '#fff3b8');
  cap.addColorStop(0.55, '#d9a21b');
  cap.addColorStop(1, '#6b4700');
  ctx.beginPath();
  ctx.arc(cx, cy, 28, 0, 2 * Math.PI);
  ctx.fillStyle = cap;
  ctx.shadowColor = 'rgba(0,0,0,0.6)';
  ctx.shadowBlur = 10;
  ctx.fill();
  ctx.shadowBlur = 0;
  ctx.strokeStyle = 'rgba(255, 244, 190, 0.9)';
  ctx.lineWidth = 1.5;
  ctx.stroke();
  ctx.font = '22px serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText('👑', cx, cy + 1);
}

function sectorIndexForResult(res) {
  const key = res.type === 'TICKET' ? 'TICKET' : `EXC:${res.excAmount}`;
  const idx = SECTORS.findIndex(s => s.key === key);
  return idx >= 0 ? idx : 0;
}

function targetRotForSector(fromRot, sectorIdx, minRevs) {
  // Стрелка сверху = 3π/2; центр сектора sectorIdx должен оказаться под ней (см. WheelPage.ClassicWheel)
  const arrow = Math.PI * 3 / 2;
  let target = arrow - (sectorIdx * SLICE + SLICE / 2);
  target = ((target % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
  const minTarget = fromRot + minRevs * 2 * Math.PI;
  while (target < minTarget) target += 2 * Math.PI;
  return target;
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

/** Премиум-колесо: спин выдаётся за просмотр рекламного ролика (награду за просмотр подтверждает сервер
 * через постбек сети, клиент только ждёт, пока спин появится в статусе). Билеты обычного колеса не тратятся. */
export default function AdWheelSection({ onBack }) {
  const canvasRef = useRef(null);
  const rotRef = useRef(0);
  const rafRef = useRef(null);
  const spinsBeforeAdRef = useRef(0);
  const oddsRef = useRef(null);
  const playParticles = useParticles();

  const [status, setStatus] = useState(null);
  const [spinning, setSpinning] = useState(false);
  const [waiting, setWaiting] = useState(false); // реклама показана, ждём подтверждения от сервера
  const [result, setResult] = useState(null);
  const [message, setMessage] = useState('');

  const redraw = useCallback(() => {
    if (canvasRef.current) drawAdWheel(canvasRef.current, rotRef.current);
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (canvas) {
      const dpr = Math.min(window.devicePixelRatio || 1, 3);
      canvas.width = Math.round(LOGICAL * dpr);
      canvas.height = Math.round(LOGICAL * dpr);
    }
    redraw();
    getAdWheelStatus().then(setStatus).catch(() => {});
    return () => cancelAnimationFrame(rafRef.current);
  }, [redraw]);

  const adsgramLeft = ADSGRAM_BLOCK_IDS.length > 0 && status ? status.remainingAdsgram : 0;
  const telegaLeft = TELEGA_AD_BLOCK_UUID && status ? status.remainingTelega : 0;
  const viewsLeft = adsgramLeft + telegaLeft;
  const spins = status ? status.spins : 0;
  const untilGuarantee = status && status.untilGuarantee ? status.untilGuarantee : null;

  async function confirmAdCredited() {
    setWaiting(true);
    // Награду за просмотр подтверждает постбек рекламной сети на сервер — обычно секунды, ждём до ~12 с
    for (let i = 0; i < 10; i++) {
      await sleep(1200);
      try {
        const s = await getAdWheelStatus();
        setStatus(s);
        if (s.spins > spinsBeforeAdRef.current) {
          setMessage('✅ Просмотр засчитан — спин ваш!');
          playParticles?.('streakBonus', 2500);
          setWaiting(false);
          return;
        }
      } catch { /* повторим на следующей итерации */ }
    }
    setMessage('Просмотр ещё подтверждается — если спин не появился, зайдите в раздел через минуту.');
    setWaiting(false);
  }

  const onAdError = useCallback(() => {
    setMessage('Не удалось показать ролик, попробуйте ещё раз чуть позже.');
    setWaiting(false);
  }, []);

  const showAdsgram = useAdsgram({ blockIds: ADSGRAM_BLOCK_IDS, onReward: confirmAdCredited, onError: onAdError });
  const showTelega = useTelegaAds({ adBlockUuid: TELEGA_AD_BLOCK_UUID, onReward: confirmAdCredited, onError: onAdError });

  async function handleWatch() {
    if (waiting || spinning || viewsLeft <= 0) return;
    setResult(null);
    setMessage('');
    setWaiting(true);
    spinsBeforeAdRef.current = spins;
    const source = adsgramLeft > 0 ? 'ADSGRAM' : 'TELEGA';
    try {
      await requestAdWatch(source, 'WHEEL');
    } catch {
      setMessage('Сейчас нет доступных показов, загляните позже.');
      setWaiting(false);
      return;
    }
    if (source === 'ADSGRAM') showAdsgram(); else showTelega();
  }

  async function handleSpin() {
    if (spinning || waiting || spins < 1) return;
    setResult(null);
    setMessage('');
    setSpinning(true);

    const easeOut = t => 1 - Math.pow(1 - t, 4);

    // Фаза 1: быстрое вращение, пока идёт запрос (2 с)
    const phase1Duration = 2000;
    const phase1Speed = 4 * 2 * Math.PI;
    const phase1StartRot = rotRef.current;
    const phase1StartTime = performance.now();

    const [res] = await Promise.all([
      spinAdWheel().catch(e => ({ success: false, message: String(e) })),
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

    // Фаза 2: плавное торможение к нужному сектору (2.4 с)
    const phase2StartRot = rotRef.current;
    const finalTarget = res.success
      ? targetRotForSector(phase2StartRot, sectorIndexForResult(res), 2)
      : phase2StartRot + 2 * 2 * Math.PI;
    const phase2Duration = 2400;
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
      setStatus(s => s ? { ...s, spins: res.spinsLeft } : s);
      playParticles?.('streakBonus', 2500);
      invalidateCache('wallet', 'profile');
    } else {
      setMessage(res.message || 'Ошибка');
      getAdWheelStatus().then(setStatus).catch(() => {});
    }
  }

  let button;
  if (spinning) {
    button = { text: '⏳ Крутится...', onClick: null, cls: 'awl-btn' };
  } else if (waiting) {
    button = { text: '⏳ Засчитываем просмотр...', onClick: null, cls: 'awl-btn' };
  } else if (spins > 0) {
    button = { text: `👑 Крутить бесплатно · осталось ${spins}`, onClick: handleSpin, cls: 'awl-btn awl-btn--gold' };
  } else if (viewsLeft > 0) {
    button = { text: '🎬 Смотреть ролик — получить спин', onClick: handleWatch, cls: 'awl-btn awl-btn--watch' };
  } else if (status) {
    button = { text: '🚫 Ролики на сегодня закончились', onClick: null, cls: 'awl-btn' };
  } else {
    button = { text: '…', onClick: null, cls: 'awl-btn' };
  }

  return (
    <div className="awl-page">
      <div className="awl-header">
        <BackButton label="Назад" onClick={onBack} />
        <h1>👑 Колесо за рекламу</h1>
        <div className="awl-chips">
          <div className="awl-chip"><span>🎫</span><span>Спинов: <strong>{status ? spins : '…'}</strong></span></div>
          <div className="awl-chip"><span>🎬</span><span>Роликов сегодня: <strong>{status ? viewsLeft : '…'}</strong></span></div>
        </div>
      </div>

      <div className="awl-stage">
        <div className="awl-halo" />
        <div className="awl-pointer" />
        <canvas ref={canvasRef} width={LOGICAL} height={LOGICAL} className={`awl-canvas${spinning ? ' awl-canvas--spin' : ''}`} />
      </div>

      <div className="awl-rare">
        👑 Джекпот 5 000 EXC — очень редкий приз (шанс 1 из 5 000).{' '}
        <button type="button" className="awl-odds-link" onClick={() => oddsRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })}>
          Шансы
        </button>
      </div>

      <button className={button.cls} onClick={button.onClick || undefined} disabled={!button.onClick}>
        {button.text}
      </button>

      {untilGuarantee && (
        <div className="awl-guarantee">
          🛡 Гарантия: приз от 100 EXC (или билет) выпадет не позже чем через {untilGuarantee} {untilGuarantee === 1 ? 'спин' : untilGuarantee < 5 ? 'спина' : 'спинов'}
        </div>
      )}

      {result && (
        <BorderBeamCard className="awl-result">
          <div className="awl-result-title">{result.jackpot ? '👑 ДЖЕКПОТ!' : '🎊 Ваш приз'}</div>
          <div className="awl-result-prize">{result.label}</div>
          <div className="awl-result-sub">
            {result.type === 'TICKET' ? 'Билет уже в разделе «Колесо фортуны»' : 'EXC зачислены на баланс'}
          </div>
        </BorderBeamCard>
      )}

      {message && <div className="awl-message">{message}</div>}

      <div className="awl-section">
        <div className="awl-section-title">Как это работает</div>
        <div className="awl-steps">
          <div className="awl-step"><span className="awl-step-n">1</span><span>Нажмите «Смотреть ролик» и досмотрите его до конца</span></div>
          <div className="awl-step"><span className="awl-step-n">2</span><span>Просмотр засчитается — вы получите спин</span></div>
          <div className="awl-step"><span className="awl-step-n">3</span><span>Крутите колесо: EXC зачисляются сразу, билеты не тратятся</span></div>
        </div>
      </div>

      <div className="awl-section" ref={oddsRef}>
        <div className="awl-section-title">Призы и шансы</div>
        <BorderBeamCard>
          <table className="awl-prize-table">
            <tbody>
              {PRIZES.map((p, i) => (
                <tr key={i}>
                  <td>{p.label}</td>
                  <td className="awl-prize-prob">{p.prob}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </BorderBeamCard>
        <div className="awl-note">
          Шансы указаны для одного спина. Джекпот выдаётся не чаще раза в 30 дней одному игроку и не более 2 раз в сутки всем игрокам;
          если лимит исчерпан, выпадает 500 EXC. Если 19 спинов подряд не выпал приз от 100 EXC или билет, следующий спин гарантированно
          даёт такой приз (не джекпот).
        </div>
        <div className="awl-note">
          Каждый просмотр идёт в общий дневной лимит рекламы: ролик даёт либо спин здесь, либо 30 EXC в разделе «Квесты».
        </div>
      </div>
    </div>
  );
}
