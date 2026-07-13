import { useEffect, useState } from 'react';
import { getShopItems, getShopStats, purchaseItem, getMyRewards, getProfile } from '../api/client';
import './QuestsPage.css';
import './ShopPage.css';

const STATUS_LABELS = {
  PENDING: 'На проверке',
  IN_PROGRESS: 'В обработке',
  APPROVED: 'Выдано',
  REJECTED: 'Отклонено',
  CANCELLED: 'Отменено',
};
const STATUS_COLORS = {
  PENDING: '#ffa726',
  IN_PROGRESS: '#ffa726',
  APPROVED: '#66bb6a',
  REJECTED: '#ef5350',
  CANCELLED: '#888',
};

function ShopItemCard({ item, expanded, onToggle, onPurchased }) {
  const [userData, setUserData] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);

  async function handleBuy() {
    if (item.userDataPrompt && !userData.trim()) {
      setMessage('Заполните поле выше.');
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const res = await purchaseItem(item.id, userData.trim() || null);
      setMessage(res.message);
      if (res.success) {
        setUserData('');
        onPurchased();
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={`shop-card ${item.locked ? 'shop-card-locked' : ''}`} onClick={() => onToggle(item.id)}>
      <div className="shop-top">
        <div className="shop-title">{item.title}</div>
        <div className="shop-price">{item.effectivePrice.toLocaleString()} EXC</div>
      </div>
      {item.statusNote && <div className="shop-status">{item.statusNote}</div>}
      {expanded && (
        <div className="shop-detail" onClick={e => e.stopPropagation()}>
          <p className="shop-desc">{item.description}</p>
          {item.userDataPrompt && (
            <>
              <div className="quest-section-title">{item.userDataPrompt}</div>
              <input
                type="text"
                className="quest-text-input"
                value={userData}
                onChange={e => setUserData(e.target.value)}
                placeholder="Введите данные"
              />
            </>
          )}
          <button className="quest-btn" disabled={busy || item.locked} onClick={handleBuy}>
            {busy ? 'Секунду...' : item.locked ? 'Недоступно' : 'Купить'}
          </button>
          {message && <div className="quest-message">{message}</div>}
        </div>
      )}
    </div>
  );
}

function ShopItemsView({ expanded, onToggle }) {
  const [items, setItems] = useState(null);
  const [stats, setStats] = useState(null);
  const [profile, setProfile] = useState(null);
  const [error, setError] = useState(null);

  function reload() {
    setError(null);
    getShopItems().then(setItems).catch(() => setError('Не удалось загрузить магазин. Попробуйте ещё раз.'));
    getShopStats().then(setStats).catch(() => {});
    getProfile().then(setProfile).catch(() => {});
  }

  useEffect(() => { reload(); }, []);

  if (error) {
    return <div className="page-center error-msg">{error}</div>;
  }
  if (items === null) {
    return <div className="page-center">Загрузка...</div>;
  }

  const grouped = items.reduce((acc, item) => {
    const cat = item.category || 'Другое';
    (acc[cat] = acc[cat] || []).push(item);
    return acc;
  }, {});

  return (
    <>
      <div className="shop-header">
        {profile && <div className="shop-balance">🪙 {profile.coins.toLocaleString()} EXC</div>}
        {stats && <div className="shop-ratio">📊 Состояние фонда: {stats.healthRatioPercent}%</div>}
      </div>

      {Object.entries(grouped).map(([cat, list]) => (
        <div key={cat} className="category-section">
          <div className="category-header">{cat}</div>
          {list.map(item => (
            <ShopItemCard
              key={item.id}
              item={item}
              expanded={expanded === item.id}
              onToggle={onToggle}
              onPurchased={reload}
            />
          ))}
        </div>
      ))}
    </>
  );
}

function MyPurchasesView() {
  const [rewards, setRewards] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    getMyRewards().then(setRewards).catch(() => setError('Не удалось загрузить заявки. Попробуйте ещё раз.'));
  }, []);

  if (error) return <div className="page-center error-msg">{error}</div>;
  if (rewards === null) return <div className="page-center">Загрузка...</div>;
  if (rewards.length === 0) {
    return <div className="page-center">📭 У вас пока нет заявок. Загляните в «Товары» и выберите награду.</div>;
  }

  return (
    <div className="category-section">
      {rewards.map(r => (
        <div key={r.id} className="shop-card">
          <div className="shop-top">
            <div className="shop-title">{r.rewardTitle}</div>
            <div className="shop-price">{r.priceCoins.toLocaleString()} EXC</div>
          </div>
          <div className="shop-meta">
            <span style={{ color: STATUS_COLORS[r.status] }}>● {STATUS_LABELS[r.status] || r.status}</span>
            <span className="shop-date">{r.createdAt}</span>
          </div>
          {r.status === 'REJECTED' && r.adminComment && (
            <div className="quest-mod-comment"><p>{r.adminComment}</p></div>
          )}
        </div>
      ))}
    </div>
  );
}

export default function ShopPage() {
  const [view, setView] = useState('items');
  const [expanded, setExpanded] = useState(null);

  function switchView(v) {
    setView(v);
    setExpanded(null);
  }

  function toggleExpand(id) {
    setExpanded(prev => (prev === id ? null : id));
  }

  return (
    <div className="quests-page shop-page">
      <div className="view-toggle">
        <button className={`view-tab ${view === 'items' ? 'active' : ''}`} onClick={() => switchView('items')}>
          Товары
        </button>
        <button className={`view-tab ${view === 'mine' ? 'active' : ''}`} onClick={() => switchView('mine')}>
          Мои заявки
        </button>
      </div>

      {view === 'items'
        ? <ShopItemsView expanded={expanded} onToggle={toggleExpand} />
        : <MyPurchasesView />}
    </div>
  );
}
