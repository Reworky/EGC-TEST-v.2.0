import { useEffect, useState } from 'react';
import { getBattlePass, purchaseBattlePass } from '../api/client';
import BackButton from '../components/BackButton';
import './QuestsPage.css';
import './ShopPage.css';
import './ReferralsPage.css';

export default function BattlePassPage() {
  const [pass, setPass] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);

  function reload() {
    setError(null);
    getBattlePass().then(setPass).catch(() => setError('Не удалось загрузить Battle Pass. Попробуйте ещё раз.'));
  }

  useEffect(() => { reload(); }, []);

  async function handlePurchase() {
    if (!pass?.seasonId) return;
    setBusy(true);
    setMessage(null);
    try {
      const res = await purchaseBattlePass(pass.seasonId);
      setMessage(res.message);
      if (res.success) reload();
    } finally {
      setBusy(false);
    }
  }

  if (error) return <div className="page-center error-msg">{error}</div>;
  if (!pass) return <div className="page-center">Загрузка...</div>;

  const canAfford = pass.userCoins >= pass.priceExc;

  return (
    <div className="quests-page shop-page">
      <div style={{ padding: '16px 16px 0' }}><BackButton to="/profile" label="Профиль" /></div>

      <div className="category-section" style={{ marginTop: 16 }}>
        {!pass.hasSeason ? (
          <div className="ref-link-card">
            <div className="ref-link-label">🎫 Battle Pass</div>
            <p className="shop-desc">⏳ Активного сезона сейчас нет. Следите за анонсами!</p>
          </div>
        ) : pass.hasActivePass ? (
          <div className="ref-link-card">
            <div className="ref-link-label">🎫 Battle Pass — активен</div>
            <p className="shop-desc">Действует до {pass.passActiveUntil}</p>
            <div className="shop-card" style={{ marginTop: 12 }}>
              <p className="shop-desc">⚡ Бонус XP за квесты: +{pass.xpBoostPercent}%</p>
              <p className="shop-desc">🌟 Доступны эксклюзивные сезонные квесты</p>
              <p className="shop-desc">👑 Значок Battle Pass в профиле и рейтинге</p>
            </div>
          </div>
        ) : (
          <div className="ref-link-card">
            <div className="ref-link-label">🎫 {pass.name}</div>
            <p className="shop-desc">⚡ Бонус XP за квесты: +{pass.xpBoostPercent}%</p>
            <p className="shop-desc">🌟 Доступны эксклюзивные сезонные квесты</p>
            <p className="shop-desc">👑 Значок Battle Pass в профиле и рейтинге</p>
            {pass.endDate && <p className="shop-desc">📅 Сезон до {pass.endDate}</p>}
            <div className="shop-top" style={{ marginTop: 12 }}>
              <div className="shop-title">Цена</div>
              <div className="shop-price">💰 {pass.priceExc.toLocaleString()} EXC</div>
            </div>
            <p className="shop-desc">Ваш баланс: {pass.userCoins.toLocaleString()} EXC</p>
            <button className="quest-btn" disabled={busy || !canAfford} onClick={handlePurchase}>
              {busy ? 'Покупка...' : canAfford ? '🎫 Купить Battle Pass' : '❌ Недостаточно EXC'}
            </button>
            {message && <div className="quest-message">{message}</div>}
          </div>
        )}
      </div>
    </div>
  );
}
