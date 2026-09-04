import { useEffect, useState } from 'react';
import { getAdStatus, requestAdWatch, invalidateCache } from '../api/client';
import { useParticles } from './ParticlesContext';
import { useAdsgram } from '../hooks/useAdsgram';

const ADSGRAM_BLOCK_ID = import.meta.env.VITE_ADSGRAM_BLOCK_ID;
const ADSGRAM_BLOCK_ID_FALLBACK = import.meta.env.VITE_ADSGRAM_BLOCK_ID_FALLBACK;

/** Оба блока по очереди пробуются как равноправные "основные" — порядок случайно перемешивается
 * при каждом открытии карточки, а не всегда 45630 первым. Это даёт им сопоставимый объём показов
 * для честного сравнения CPM (второй блок в цепочке остаётся подстраховкой на случай ошибки первого). */
function pickBlockOrder() {
  const ids = [ADSGRAM_BLOCK_ID, ADSGRAM_BLOCK_ID_FALLBACK].filter(Boolean);
  return Math.random() < 0.5 ? ids : ids.slice().reverse();
}

/** Карточка "Смотреть рекламу" — 4-й раздел на странице Квестов (см. QuestsPage), оформлена как
 * обычная quest-card для единообразия с соседними разделами. Бот открывает мини-апп webApp-кнопкой
 * прямо на /quests?section=ads, чтобы этот раздел был сразу развёрнут. */
export default function AdRewardCard() {
  const [remaining, setRemaining] = useState(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);
  const playParticles = useParticles();

  useEffect(() => { getAdStatus().then(s => setRemaining(s.remainingToday)).catch(() => {}); }, []);

  const [blockOrder] = useState(pickBlockOrder);
  const showAd = useAdsgram({
    blockIds: blockOrder,
    onReward: () => {
      setMessage('✅ Награда начислена!');
      playParticles?.('streakBonus', 3000);
      setTimeout(() => {
        invalidateCache('wallet');
        getAdStatus().then(s => setRemaining(s.remainingToday)).catch(() => {});
      }, 1500);
      setBusy(false);
    },
    onError: () => {
      setMessage('Не удалось показать рекламу, попробуйте ещё раз позже.');
      setBusy(false);
    },
  });

  async function handleClick() {
    setBusy(true);
    setMessage(null);
    try {
      await requestAdWatch();
      showAd();
    } catch {
      setMessage('Сейчас нет доступных показов, загляните позже.');
      setBusy(false);
    }
  }

  const available = remaining !== null && remaining > 0;

  return (
    <div className="quest-card q-ads">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <span className="quest-cat-badge ads">🎬 Реклама</span>
      </div>

      <div className="quest-top">
        <div className="quest-title">Посмотри рекламу — получи EXC</div>
        <div className="quest-rewards">
          <span className="reward-exc"><i className="ti ti-coin"></i> +30 EXC</span>
        </div>
      </div>

      <div className="quest-meta" style={{ marginBottom: 12 }}>
        {remaining === null ? (
          <span>Загрузка...</span>
        ) : (
          <span>⏱ Осталось сегодня: {remaining}/5</span>
        )}
      </div>

      {remaining !== null && (
        available ? (
          <button className="quest-btn" disabled={busy} onClick={handleClick}>
            {busy ? 'Секунду...' : <><i className="ti ti-player-play" style={{ marginRight: 6 }} /> Смотреть</>}
          </button>
        ) : (
          <div className="quest-status quest-status-pending">
            <i className="ti ti-circle-check"></i> Лимит показов на сегодня исчерпан. Возвращайтесь завтра.
          </div>
        )
      )}
      {message && <div className="quest-message">{message}</div>}
    </div>
  );
}
