import { useEffect, useState } from 'react';
import { getAdStatus, requestAdWatch, invalidateCache } from '../api/client';
import BackButton from '../components/BackButton';
import ShimmerButton from '../components/ShimmerButton';
import { useParticles } from '../components/ParticlesContext';
import { useAdsgram } from '../hooks/useAdsgram';
import './QuestsPage.css';
import './ShopPage.css';
import './ReferralsPage.css';
import './WalletPage.css';

const ADSGRAM_BLOCK_ID = import.meta.env.VITE_ADSGRAM_BLOCK_ID;

export default function AdsPage() {
  const [remaining, setRemaining] = useState(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);
  const playParticles = useParticles();

  useEffect(() => { getAdStatus().then(s => setRemaining(s.remainingToday)).catch(() => {}); }, []);

  const showAd = useAdsgram({
    blockId: ADSGRAM_BLOCK_ID,
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
    <div className="quests-page shop-page">
      <div style={{ padding: '16px 16px 0' }}><BackButton to="/quests" label="Квесты" /></div>

      <div className="ref-link-card" style={{ margin: '16px' }}>
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
    </div>
  );
}
