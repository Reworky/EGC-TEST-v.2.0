import { useEffect, useState } from 'react';
import { getShopItems, getShopStats, purchaseItem, getMyRewards, getProfile, getPerksState, purchasePerk, sendGiftBoost, cancelReward } from '../api/client';
import './QuestsPage.css';
import './ShopPage.css';

const PERK_CATEGORIES = [
  {
    title: 'Бусты',
    items: [
      { key: 'xpboost24', title: '⚡ XP +20% • 24ч', price: 3000, blockedBy: 'xpBoostActive', activeUntilField: 'xpBoostUntil' },
      { key: 'xpboost72', title: '⚡ XP +20% • 72ч', price: 7500, blockedBy: 'xpBoostActive', activeUntilField: 'xpBoostUntil' },
      { key: 'excboost24', title: '⚡ EXC +20% • 24ч', price: 3000, blockedBy: 'excBoostActive', activeUntilField: 'excBoostUntil' },
      { key: 'excboost72', title: '⚡ EXC +20% • 72ч', price: 7500, blockedBy: 'excBoostActive', activeUntilField: 'excBoostUntil' },
      { key: 'doubleboost24', title: '⚡⚡ Двойной буст • 24ч', price: 5000, hideIf: s => s.xpBoostActive || s.excBoostActive },
    ],
  },
  {
    title: 'Квесты',
    items: [
      { key: 'reroll', title: '🔀 Реролл квеста', price: 2000, description: 'Заменяет ваш текущий набор доступных квестов на новый.' },
      { key: 'insurance', title: '🛡️ Страховка провала', price: 1500, blockedBy: 'insuranceActive', description: 'Если следующий отчёт отклонят — сможете отправить его повторно без штрафа.' },
      { key: 'extraslot', title: '📂 Доп. слот квеста 48ч', price: 2000, blockedBy: 'extraSlotActive', activeUntilField: 'extraSlotUntil', description: 'Позволяет вести 3 квеста одновременно вместо 1.' },
      { key: 'cooldown', title: '⏱️ Снятие кулдауна', price: 1500, blockedBy: 'cooldownBypassActive', description: 'Снимает кулдаун для следующего взятого квеста в любой игре. Лимит: 2 раза в сутки.' },
    ],
  },
  {
    title: 'Кастомизация',
    items: [
      { key: 'title_basic', title: '🌱 Новый игрок', price: 1500, description: 'Титул отображается в профиле.' },
      { key: 'title_rare', title: '🔥 Квест-хантер', price: 4500, description: 'Титул отображается в профиле.' },
      { key: 'title_epic', title: '👑 Элита клуба', price: 7500, description: 'Титул отображается в профиле.' },
    ],
  },
];

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
  const [showFundInfo, setShowFundInfo] = useState(false);

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

  // Кастомизация (рамки аватара) объединена с титулами в разделе «Предметы» — здесь не дублируем.
  const grouped = items.filter(item => item.category !== 'Кастомизация').reduce((acc, item) => {
    const cat = item.category || 'Другое';
    (acc[cat] = acc[cat] || []).push(item);
    return acc;
  }, {});

  return (
    <>
      <div className="shop-header">
        {profile && <div className="shop-balance">🪙 {profile.coins.toLocaleString()} EXC</div>}
        {stats && (
        <>
          <div className="shop-ratio" onClick={() => setShowFundInfo(true)} style={{ cursor: 'pointer' }}>
            📊 Состояние фонда: {stats.healthRatioPercent}%
          </div>
          {showFundInfo && (
            <div className="fund-modal-overlay" onClick={() => setShowFundInfo(false)}>
              <div className="fund-modal" onClick={e => e.stopPropagation()}>
                <div className="fund-modal-title">📊 Состояние фонда</div>
                <p className="fund-modal-text">
                  Фонд клуба — это пул рублей, из которого выплачиваются награды игрокам.
                </p>
                <p className="fund-modal-text">
                  <b>Текущее состояние: {stats.healthRatioPercent}%</b><br />
                  Это соотношение реальных средств в фонде к общей сумме EXC у игроков.
                  Чем ниже — тем выше цены в магазине: так клуб балансирует нагрузку на фонд.
                </p>
                <p className="fund-modal-text">
                  При 100% все цены базовые. При снижении фонда цены растут пропорционально,
                  чтобы поддерживать возможность выплат всем игрокам.
                </p>
                <button className="fund-modal-close" onClick={() => setShowFundInfo(false)}>Понятно</button>
              </div>
            </div>
          )}
        </>
      )}
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

function PerkCard({ item, state, expanded, onToggle, onPurchased }) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);
  const active = item.blockedBy && state[item.blockedBy];
  const untilText = active && item.activeUntilField ? state[item.activeUntilField] : null;

  async function handleBuy() {
    setBusy(true);
    setMessage(null);
    try {
      const res = await purchasePerk(item.key);
      setMessage(res.message);
      if (res.success) onPurchased();
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={`shop-card ${active ? 'shop-card-locked' : ''}`} onClick={() => onToggle(item.key)}>
      <div className="shop-top">
        <div className="shop-title">{item.title}</div>
        <div className="shop-price">{item.price.toLocaleString()} EXC</div>
      </div>
      {active && (
        <div className="shop-status">✅ Активен{untilText ? ` до ${untilText}` : ''}</div>
      )}
      {expanded && (
        <div className="shop-detail" onClick={e => e.stopPropagation()}>
          {item.description && <p className="shop-desc">{item.description}</p>}
          <button className="quest-btn" disabled={busy || active} onClick={handleBuy}>
            {busy ? 'Секунду...' : active ? 'Уже активен' : 'Купить'}
          </button>
          {message && <div className="quest-message">{message}</div>}
        </div>
      )}
    </div>
  );
}

function GiftCard({ expanded, onToggle }) {
  const [nickname, setNickname] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);

  async function handleSend() {
    if (!nickname.trim()) {
      setMessage('Введите ник получателя.');
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const res = await sendGiftBoost(nickname.trim());
      setMessage(res.message);
      if (res.success) setNickname('');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="shop-card" onClick={() => onToggle('gift')}>
      <div className="shop-top">
        <div className="shop-title">🎁 Подарок другу (буст)</div>
        <div className="shop-price">4 500 EXC</div>
      </div>
      {expanded && (
        <div className="shop-detail" onClick={e => e.stopPropagation()}>
          <p className="shop-desc">Отправляет XP-буст на 24 часа другому игроку. Лимит: 2 подарка в сутки.</p>
          <input
            type="text"
            className="quest-text-input"
            value={nickname}
            onChange={e => setNickname(e.target.value)}
            placeholder="Ник получателя (как в профиле бота)"
          />
          <button className="quest-btn" disabled={busy} onClick={handleSend}>
            {busy ? 'Секунду...' : 'Отправить'}
          </button>
          {message && <div className="quest-message">{message}</div>}
        </div>
      )}
    </div>
  );
}

function PerksView({ expanded, onToggle }) {
  const [state, setState] = useState(null);
  const [frames, setFrames] = useState(null);
  const [error, setError] = useState(null);

  function reload() {
    setError(null);
    getPerksState().then(setState).catch(() => setError('Не удалось загрузить предметы. Попробуйте ещё раз.'));
    getShopItems().then(items => setFrames(items.filter(i => i.category === 'Кастомизация'))).catch(() => setFrames([]));
  }

  useEffect(() => { reload(); }, []);

  if (error) return <div className="page-center error-msg">{error}</div>;
  if (state === null) return <div className="page-center">Загрузка...</div>;

  return (
    <>
      <div className="shop-header">
        <div className="shop-balance">🪙 {state.coins.toLocaleString()} EXC</div>
        {state.profileTitle && <div className="shop-ratio">🏅 {state.profileTitle}</div>}
      </div>

      {PERK_CATEGORIES.map(cat => {
        const visible = cat.items.filter(item => !item.hideIf || !item.hideIf(state));
        const isCustomization = cat.title === 'Кастомизация';
        if (visible.length === 0 && !(isCustomization && frames?.length)) return null;
        return (
          <div key={cat.title} className="category-section">
            <div className="category-header">{cat.title}</div>
            {visible.map(item => (
              <PerkCard
                key={item.key}
                item={item}
                state={state}
                expanded={expanded === item.key}
                onToggle={onToggle}
                onPurchased={reload}
              />
            ))}
            {isCustomization && frames?.map(item => (
              <ShopItemCard
                key={`frame-${item.id}`}
                item={item}
                expanded={expanded === item.id}
                onToggle={onToggle}
                onPurchased={reload}
              />
            ))}
          </div>
        );
      })}

      <div className="category-section">
        <div className="category-header">Социальные</div>
        <GiftCard expanded={expanded === 'gift'} onToggle={onToggle} />
      </div>
    </>
  );
}

function MyPurchasesView() {
  const [rewards, setRewards] = useState(null);
  const [error, setError] = useState(null);
  const [busyId, setBusyId] = useState(null);

  function reload() {
    setError(null);
    getMyRewards().then(setRewards).catch(() => setError('Не удалось загрузить заявки. Попробуйте ещё раз.'));
  }

  useEffect(() => { reload(); }, []);

  async function handleCancel(id) {
    setBusyId(id);
    try {
      await cancelReward(id);
      reload();
    } finally {
      setBusyId(null);
    }
  }

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
          {r.status === 'PENDING' && (
            <button className="quest-btn quest-btn-secondary" disabled={busyId === r.id} onClick={() => handleCancel(r.id)}>
              {busyId === r.id ? 'Секунду...' : 'Отменить заявку'}
            </button>
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
        <button className={`view-tab ${view === 'perks' ? 'active' : ''}`} onClick={() => switchView('perks')}>
          Предметы
        </button>
        <button className={`view-tab ${view === 'mine' ? 'active' : ''}`} onClick={() => switchView('mine')}>
          Мои заявки
        </button>
      </div>

      {view === 'items' && <ShopItemsView expanded={expanded} onToggle={toggleExpand} />}
      {view === 'perks' && <PerksView expanded={expanded} onToggle={toggleExpand} />}
      {view === 'mine' && <MyPurchasesView />}
    </div>
  );
}
