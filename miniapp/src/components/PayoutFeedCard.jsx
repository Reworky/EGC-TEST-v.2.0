import { useEffect, useState } from 'react';
import { getPayoutFeed } from '../api/client';

const METHOD_LABEL = { stars: '⭐ Stars', ton: 'GRAM', sbp: 'СБП' };

function agoText(minutes) {
  if (minutes == null || minutes < 0) return '';
  if (minutes < 1) return 'только что';
  if (minutes < 60) return `${minutes} мин назад`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} ч назад`;
  return `${Math.floor(hours / 24)} дн. назад`;
}

function rub(n) {
  return `${Number(n || 0).toLocaleString('ru-RU')} ₽`;
}

/** «Недавно выплатили»: та же лента, что в боте (последние выплаты с замаскированными никами, итоги за 24 ч и 7 дней). */
export default function PayoutFeedCard() {
  const [feed, setFeed] = useState(null);

  useEffect(() => {
    let alive = true;
    getPayoutFeed().then(d => { if (alive) setFeed(d); }).catch(() => {});
    return () => { alive = false; };
  }, []);

  // Пока грузится или сервер недоступен — блока просто нет (лента не критична для кошелька).
  if (!feed) return null;

  function openReviews() {
    const url = feed.reviewsUrl || 'https://t.me/egc_payouts';
    const tg = window.Telegram?.WebApp;
    if (tg) tg.openTelegramLink(url); else window.open(url, '_blank', 'noopener');
  }

  const items = feed.items || [];

  return (
    <div className="ref-progress-card pf-card">
      <div className="pf-title">💸 Недавно выплатили</div>
      {items.length === 0 ? (
        <div className="pf-empty">Пока выплат нет — первые скоро появятся здесь.</div>
      ) : (
        <div className="pf-list">
          {items.map((it, i) => (
            <div key={i} className="pf-row">
              <span className="pf-amount">{it.amountText || rub(it.rub)}</span>
              <span className="pf-method">{METHOD_LABEL[it.method] || it.method}</span>
              <span className="pf-meta">{[agoText(it.minutesAgo), it.name].filter(Boolean).join(' · ')}</span>
            </div>
          ))}
        </div>
      )}
      {feed.weekCount > 0 && (
        <div className="pf-totals">
          <div>📊 За 24 часа: <b>{rub(feed.dayRub)}</b> · {feed.dayCount} выплат</div>
          <div>📊 За 7 дней: <b>{rub(feed.weekRub)}</b> · {feed.weekCount} выплат</div>
        </div>
      )}
      <button type="button" className="pf-reviews" onClick={openReviews}>⭐ Отзывы игроков →</button>
    </div>
  );
}
