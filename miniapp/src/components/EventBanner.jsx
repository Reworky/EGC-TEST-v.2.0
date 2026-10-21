import { useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { getCurrentEvent } from '../api/client';
import './EventBanner.css';

// Сезонный баннер (Хэллоуин-2026, ТЗ пункт 5): компактная плашка с таймером под акцентным цветом темы. На «Рефералах» показывается буст-уикенд,
// поэтому там баннер события скрыт. Если события нет или оно выключено/закончилось, компонент ничего не рисует (никаких следов).
function formatLeft(endsAtIso) {
  const diff = new Date(endsAtIso).getTime() - Date.now();
  if (diff <= 0) return null;
  const totalMinutes = Math.floor(diff / 60000);
  const d = Math.floor(totalMinutes / 1440);
  const h = String(Math.floor((totalMinutes % 1440) / 60)).padStart(2, '0');
  const m = String(totalMinutes % 60).padStart(2, '0');
  return d > 0 ? `${d}д ${h}:${m}` : `${h}:${m}`;
}

export default function EventBanner() {
  const [ev, setEv] = useState(null);
  const [left, setLeft] = useState(null);
  const { pathname } = useLocation();

  useEffect(() => {
    getCurrentEvent().then(setEv).catch(() => setEv(null));
  }, []);

  useEffect(() => {
    if (!ev?.active || !ev?.endsAt) { setLeft(null); return; }
    const tick = () => setLeft(formatLeft(ev.endsAt));
    tick();
    const timer = setInterval(tick, 30000);
    return () => clearInterval(timer);
  }, [ev]);

  if (!ev?.active || !left || pathname.startsWith('/referrals')) return null;
  return (
    <div className={`event-banner event-theme-${ev.themeKey || 'default'}`}>
      <span className="event-banner-title">{ev.themeKey === 'halloween' ? '🎃 ' : ''}{ev.title}</span>
      <span className="event-banner-timer">осталось {left}</span>
    </div>
  );
}
