import { useEffect, useState } from 'react';
import { getBattlePass, getProfile, getStarsPrices, invalidateCache } from '../api/client';
import BackButton from '../components/BackButton';
import EgcPassCard from '../components/EgcPassCard';
import './QuestsPage.css';
import './ShopPage.css';
import './ReferralsPage.css';

/** Раздел «EGC Pass» — единый платный продукт (2026-09-24, решение владельца: Battle Pass объединён с
 *  подпиской). Без подписки — предложение оформить, с подпиской — статус и срок. Прежний Battle Pass, купленный
 *  за EXC до объединения, доживает свой срок — о нём одна строка ниже карточки (покупка отключена). */
export default function EgcPassPage() {
  const [profile, setProfile] = useState(null);
  const [legacyPass, setLegacyPass] = useState(null);
  const [price, setPrice] = useState(150);
  const [error, setError] = useState(null);

  function reload() {
    setError(null);
    getProfile().then(setProfile).catch(() => setError('Не удалось загрузить данные. Попробуйте ещё раз.'));
    getBattlePass().then(setLegacyPass).catch(() => {});
    getStarsPrices().then(p => { if (p?.EGC_PASS) setPrice(p.EGC_PASS); }).catch(() => {});
  }

  // После оплаты подписки профиль надо перечитать с сервера, а не из кэша — иначе карточка продолжила бы
  // предлагать «Оформить», хотя подписка уже активна.
  async function handlePurchased() {
    invalidateCache('profile');
    await getProfile().then(setProfile).catch(() => {});
  }

  useEffect(() => { reload(); }, []);

  if (error) return <div className="page-center error-msg">{error}</div>;
  if (!profile) return <div className="page-center">Загрузка...</div>;

  return (
    <div className="quests-page shop-page">
      <div style={{ padding: '16px 16px 0' }}><BackButton to="/profile" label="Профиль" /></div>

      <div style={{ marginTop: 16 }}>
        <EgcPassCard profile={profile} price={price} onPurchased={handlePurchased} />
      </div>

      {legacyPass?.hasActivePass && (
        <div className="category-section">
          <div className="ref-link-card">
            <div className="ref-link-label">🎫 Прежний Battle Pass</div>
            <p className="shop-desc">
              Куплен за EXC до объединения с EGC Pass — действует до {legacyPass.passActiveUntil}, его XP-буст и значок
              сохраняются до конца срока. Новые Battle Pass не продаются: его перки теперь входят в EGC Pass.
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
