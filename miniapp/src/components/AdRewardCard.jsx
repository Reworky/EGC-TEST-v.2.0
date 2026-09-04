import { useEffect, useState } from 'react';
import { getAdStatus, requestAdWatch, invalidateCache } from '../api/client';
import ShimmerButton from './ShimmerButton';
import { useParticles } from './ParticlesContext';
import { useAdsgram } from '../hooks/useAdsgram';

const ADSGRAM_BLOCK_ID = import.meta.env.VITE_ADSGRAM_BLOCK_ID;
const ADSGRAM_BLOCK_ID_FALLBACK = import.meta.env.VITE_ADSGRAM_BLOCK_ID_FALLBACK;

/** Карточка "Смотреть рекламу" — переиспользуется на отдельной странице /ads (открывается из бота
 * webApp-кнопкой) и прямо внутри Квестов мини-аппа (см. QuestsPage). */
export default function AdRewardCard() {
  const [remaining, setRemaining] = useState(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);
  const playParticles = useParticles();

  useEffect(() => { getAdStatus().then(s => setRemaining(s.remainingToday)).catch(() => {}); }, []);

  const showAd = useAdsgram({
    blockIds: [ADSGRAM_BLOCK_ID, ADSGRAM_BLOCK_ID_FALLBACK],
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

  return (
    <div className="ref-link-card">
      <div className="ref-link-label">Смотреть рекламу</div>
      {remaining === null ? (
        <p className="shop-desc">Загрузка...</p>
      ) : remaining > 0 ? (
        <>
          <p className="shop-desc"><i className="ti ti-video"></i> +30 EXC за просмотр · Осталось сегодня: {remaining}/5</p>
          <ShimmerButton disabled={busy} onClick={handleClick}>
            {busy ? 'Секунду...' : <><i className="ti ti-player-play" style={{ marginRight: 6 }} /> Смотреть</>}
          </ShimmerButton>
        </>
      ) : (
        <p className="shop-desc"><i className="ti ti-circle-check"></i> Лимит показов на сегодня исчерпан. Возвращайтесь завтра.</p>
      )}
      {message && <div className="quest-message">{message}</div>}
    </div>
  );
}
