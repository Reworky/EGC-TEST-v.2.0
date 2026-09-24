import { useEffect, useState } from 'react';
import { getWallet, getShopItems, getShopStats, purchaseItem, getMyRewards, getProfile, getPerksState, purchasePerk, sendGiftBoost, sendExcTransfer, cancelReward, equipFrame, getStarsInvoiceLink, getStarsPrices } from '../api/client';
import { openStarsInvoice } from '../utils/stars';
import BackButton from '../components/BackButton';
import AdBanner from '../components/AdBanner';
import DonateView from './DonateView';
import './QuestsPage.css';
import './ShopPage.css';

const PERK_CATEGORIES = [
  {
    title: 'Бусты',
    items: [
      { key: 'xpboost24', title: '⚡ XP +20% • 24ч', price: 4000, blockedBy: 'xpBoostActive', activeUntilField: 'xpBoostUntil' },
      { key: 'xpboost72', title: '⚡ XP +20% • 72ч', price: 10000, blockedBy: 'xpBoostActive', activeUntilField: 'xpBoostUntil' },
      { key: 'excboost24', title: '⚡ EXC +20% • 24ч', price: 4000, blockedBy: 'excBoostActive', activeUntilField: 'excBoostUntil' },
      { key: 'excboost72', title: '⚡ EXC +20% • 72ч', price: 10000, blockedBy: 'excBoostActive', activeUntilField: 'excBoostUntil' },
      { key: 'doubleboost24', title: '⚡⚡ Двойной буст • 24ч', price: 6500, hideIf: s => s.xpBoostActive || s.excBoostActive },
    ],
  },
  {
    title: 'Квесты',
    items: [
      { key: 'reroll', title: '🔀 Реролл квеста', price: 2000, description: 'Заменяет ваш текущий набор доступных квестов на новый.' },
      { key: 'insurance', title: '🛡️ Страховка провала', price: 1500, blockedBy: 'insuranceActive', description: 'Если следующий отчёт отклонят — сможете отправить его повторно без штрафа.' },
      { key: 'extraslot', title: '📂 Доп. слот квеста 48ч', price: 3500, blockedBy: 'extraSlotActive', activeUntilField: 'extraSlotUntil', description: 'Позволяет вести до 3 квестов одновременно.' },
      { key: 'cooldown', title: '⏱️ Снятие кулдауна', price: 3000, blockedBy: 'cooldownBypassActive', activeLabel: 'Ждёт квест с кулдауном', description: 'Снимает кулдаун для следующего квеста в любой игре. Применится автоматически при взятии квеста с кулдауном. Не снимает отдельный лимит «1 квест в час» между любыми квестами. Лимит: 2 раза в сутки.' },
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

// Игры с донатом за реальные деньги (Купикод) — как в боте: они закреплены наверху Магазина в этом
// порядке (Brawl Stars → Clash Royale → Clash of Clans), остальные категории идут следом. Ключ —
// название категории товара, значение — gameKey каталога доната (GemPurchaseService).
const DONATE_GAMES = {
  'Brawl Stars': 'brawl_stars',
  'Clash Royale': 'clash_royale',
  'Clash of Clans': 'clash_of_clans',
};

const STATUS_LABELS = {
  PENDING: <><i className="ti ti-clock"></i> На проверке</>,
  IN_PROGRESS: <><i className="ti ti-clock"></i> В обработке</>,
  APPROVED: <><i className="ti ti-circle-check"></i> Выдано</>,
  REJECTED: <><i className="ti ti-circle-x"></i> Отклонено</>,
  CANCELLED: <><i className="ti ti-circle-x"></i> Отменено</>,
};
const STATUS_COLORS = {
  PENDING: '#ffa726',
  IN_PROGRESS: '#ffa726',
  APPROVED: '#66bb6a',
  REJECTED: '#ef5350',
  CANCELLED: '#888',
};

function ShopItemCard({ item, expanded, onToggle, onPurchased, ownedFrames, activeFrame }) {
  const [userData, setUserData] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);

  const frameKey = item.avatarFrameImage;
  const isFrame = !!frameKey;
  const isOwned = isFrame && ownedFrames?.includes(frameKey);
  const isActive = isFrame && activeFrame === frameKey;

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

  async function handleEquip() {
    setBusy(true);
    setMessage(null);
    try {
      await equipFrame(frameKey);
      setMessage('✅ Рамка надета!');
      onPurchased();
    } catch {
      setMessage('Ошибка. Попробуйте ещё раз.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={`shop-card ${item.locked ? 'shop-card-locked' : ''}`} onClick={() => onToggle(item.id)}>
      <div className="shop-top">
        <div className="shop-title">{item.title}</div>
        {isOwned
          ? <div className="shop-price" style={{ color: isActive ? '#22c55e' : '#a855f7' }}>{isActive ? '✓ Надета' : 'В коллекции'}</div>
          : <div className="shop-price">{item.effectivePrice.toLocaleString()} EXC</div>
        }
      </div>
      {item.statusNote && <div className="shop-status">{item.statusNote}</div>}
      {expanded && (
        <div className="shop-detail" onClick={e => e.stopPropagation()}>
          <p className="shop-desc">{item.description}</p>
          {!isOwned && item.userDataPrompt && (
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
          {isOwned ? (
            <button className="quest-btn" disabled={busy || isActive} onClick={handleEquip}
              style={isActive ? { opacity: 0.5 } : {}}>
              {busy ? 'Секунду...' : isActive ? 'Уже надета' : 'Надеть'}
            </button>
          ) : (
            <button className="quest-btn" disabled={busy || item.locked} onClick={handleBuy}>
              {busy ? 'Секунду...' : item.locked ? 'Недоступно' : 'Купить'}
            </button>
          )}
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
  const [donateGame, setDonateGame] = useState(null);

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

  if (donateGame) {
    return <DonateView gameKey={donateGame} onBack={() => setDonateGame(null)} />;
  }

  // Кастомизация (рамки аватара) объединена с титулами в разделе «Предметы» — здесь не дублируем.
  const groupedRaw = items.filter(item => item.category !== 'Кастомизация').reduce((acc, item) => {
    const cat = item.category || 'Другое';
    (acc[cat] = acc[cat] || []).push(item);
    return acc;
  }, {});
  // Донат-игры закреплены наверху в фиксированном порядке (как в боте), остальные — как раньше.
  const grouped = {};
  Object.keys(DONATE_GAMES).forEach(cat => { if (groupedRaw[cat]) grouped[cat] = groupedRaw[cat]; });
  Object.keys(groupedRaw).forEach(cat => { if (!grouped[cat]) grouped[cat] = groupedRaw[cat]; });

  return (
    <>
      <div style={{ padding: '12px 16px 0' }}><BackButton label="Назад" /></div>
      <div className="shop-header">
        {profile && <div className="shop-balance"><i className="ti ti-coin"></i> {profile.coins.toLocaleString()} EXC</div>}
        {stats && (
        <>
          <div className="shop-ratio" onClick={() => setShowFundInfo(true)} style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '4px' }}>
            📊 Состояние фонда: {stats.healthRatioPercent}% <span style={{ fontSize: '11px', opacity: 0.6 }}>ⓘ</span>
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

      <div style={{ margin: '12px 16px 0' }}>
        <AdBanner
          img="https://aflink.ru/b/vgbqo405hfe999950e957b456b25e2/"
          link="https://heqgr.com/g/vgbqo405hfe999950e957b456b25e2/?i=4&erid=2bL9aMPo2e49hMef4rqyCwDQyf"
          alt="Zaka-zaka [CPS] RU + CIS"
          erid="2bL9aMPo2e49hMef4rqyCwDQyf"
        />
      </div>

      {Object.entries(grouped).map(([cat, list]) => (
        <div key={cat} className="category-section">
          <div className="category-header">{cat}</div>
          {DONATE_GAMES[cat] && (
            <button className="quest-btn" style={{ marginBottom: 12 }} onClick={() => setDonateGame(DONATE_GAMES[cat])}>
              💰 Купить за реальные деньги (Stars / GRAM)
            </button>
          )}
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
        <div className="shop-status"><i className="ti ti-circle-check"></i> {item.key === 'extraslot' && state.extraSlotFromEgcPass
          ? 'Включён в EGC Pass ⭐'
          : (item.activeLabel || ('Активен' + (untilText ? ` до ${untilText}` : '')))}</div>
      )}
      {expanded && (
        <div className="shop-detail" onClick={e => e.stopPropagation()}>
          {item.description && <p className="shop-desc">{item.description}</p>}
          <button className="quest-btn" disabled={busy || active} onClick={handleBuy}>
            {busy ? 'Секунду...' : active ? (item.key === 'extraslot' && state.extraSlotFromEgcPass ? 'Включён в EGC Pass' : (item.activeLabel || 'Уже активен')) : 'Купить'}
          </button>
          {message && <div className="quest-message">{message}</div>}
        </div>
      )}
    </div>
  );
}

function TransferCard({ expanded, onToggle }) {
  const [step, setStep] = useState(1);
  const [nickname, setNickname] = useState('');
  const [amount, setAmount] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);

  const MIN = 1000, MAX = 10000;
  const commission = amount ? Math.max(Math.round(Number(amount) * 0.10), 200) : 0;
  const total = amount ? Number(amount) + commission : 0;

  function reset() { setStep(1); setNickname(''); setAmount(''); setMessage(null); }

  async function handleConfirm() {
    setBusy(true);
    setMessage(null);
    try {
      const res = await sendExcTransfer(nickname.trim(), Number(amount));
      setMessage(res.message);
      if (res.success) reset();
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="shop-card" onClick={() => { if (!expanded) { reset(); onToggle('transfer'); } }}>
      <div className="shop-top">
        <div className="shop-title">🔄 Перевод EXC другу</div>
        <div className="shop-price">10% комиссия</div>
      </div>
      {expanded && (
        <div className="shop-detail" onClick={e => e.stopPropagation()}>
          <p className="shop-desc">Перевод от 1 000 до 10 000 EXC. Комиссия: 10% (мин. 200 EXC).</p>
          {step === 1 && (
            <>
              <input
                type="text"
                className="quest-text-input"
                value={nickname}
                onChange={e => setNickname(e.target.value)}
                placeholder="Ник получателя (как в профиле бота)"
              />
              <button className="quest-btn" disabled={!nickname.trim()} onClick={() => setStep(2)}>
                Далее
              </button>
            </>
          )}
          {step === 2 && (
            <>
              <input
                type="number"
                className="quest-text-input"
                value={amount}
                onChange={e => setAmount(e.target.value)}
                placeholder="Сумма (1 000 – 10 000 EXC)"
                min={MIN}
                max={MAX}
              />
              {amount && Number(amount) >= MIN && Number(amount) <= MAX && (
                <div className="quest-message" style={{ color: '#a0a4c0', marginBottom: 8 }}>
                  Получит: {Number(amount).toLocaleString()} EXC · Комиссия: {commission.toLocaleString()} EXC · Итого: {total.toLocaleString()} EXC
                </div>
              )}
              <div style={{ display: 'flex', gap: 8 }}>
                <button className="quest-btn" style={{ flex: 1, background: '#444' }} onClick={() => setStep(1)}>Назад</button>
                <button
                  className="quest-btn"
                  style={{ flex: 2 }}
                  disabled={busy || !amount || Number(amount) < MIN || Number(amount) > MAX}
                  onClick={handleConfirm}
                >
                  {busy ? 'Секунду...' : 'Перевести'}
                </button>
              </div>
            </>
          )}
          {message && <div className="quest-message" style={{ marginTop: 8 }}>{message}</div>}
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


/** Общая карточка покупки за Telegram Stars (Telegram.WebApp.openInvoice) — переиспользуется для
 * товаров, у которых нет собственного EXC-аналога в PERK_CATEGORIES (титул, доп. слот навсегда).
 * Раньше жили на главной странице профиля — перенесены сюда по просьбе (2026-09-15): это покупка,
 * ей место в магазине, а не на главной. Стилизована как обычная shop-card (2026-09-15) — раньше
 * была отдельным фиолетовым блоком и визуально "выделялась" на фоне остальных EXC-товаров того же
 * раздела, хотя это просто ещё один товар с другим способом оплаты. */
function StarsShopCard({ itemType, icon, title, description, price, onPurchased, successMessage, expanded, onToggle }) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);

  async function handleBuy() {
    setBusy(true);
    setMessage(null);
    try {
      const invoice = await getStarsInvoiceLink(itemType);
      if (!invoice.success) {
        setMessage(invoice.message);
        return;
      }
      const status = await openStarsInvoice(invoice.url);
      if (status === 'paid') {
        setMessage(successMessage);
        await new Promise(r => setTimeout(r, 800));
        await onPurchased();
      } else if (status === 'failed') {
        setMessage('Платёж не прошёл. Попробуйте ещё раз.');
      }
    } catch (e) {
      setMessage(e.message || 'Не удалось открыть оплату.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="shop-card" onClick={() => onToggle(`stars-${itemType}`)}>
      <div className="shop-top">
        <div className="shop-title">{icon} {title}</div>
        <div className="shop-price">{price} ⭐</div>
      </div>
      {expanded && (
        <div className="shop-detail" onClick={e => e.stopPropagation()}>
          <p className="shop-desc">{description}</p>
          <button className="quest-btn" disabled={busy} onClick={handleBuy}>
            {busy ? 'Секунду...' : 'Купить'}
          </button>
          {message && <div className="quest-message">{message}</div>}
        </div>
      )}
    </div>
  );
}

// Дефолты на случай, если /api/stars/prices ещё не успел загрузиться (или недоступен) — совпадают с
// ценами на момент написания, но являются только запасным вариантом: реальный источник правды —
// бэкенд (см. getStarsPrices), чтобы не разъезжаться с каталогом STARS_ITEMS при изменении цены там.
const STARS_PRICE_FALLBACK = { AVATAR_FRAME: 35, PATRON_TITLE: 60, PERMANENT_SLOT: 75, CHEST_REROLL: 15 };

// Метаданные для больших кнопок-разделов на главном экране "Предметы" (2026-09-19, по референсу
// игрока) — иконка/градиент/подпись для каждого раздела. Сами товары внутри разделов не изменились,
// просто раньше все показывались одним длинным списком, теперь сначала выбор раздела, затем список.
const SECTION_META = {
  'Мои предметы': { icon: '🎒', gradient: 'teal', subtitle: 'Всё, что у вас есть: рамки, титулы, бусты, билеты' },
  'Бусты': { icon: '⚡', gradient: 'gold', subtitle: 'Временные ускорители XP и EXC' },
  'Квесты': { icon: '🎯', gradient: 'purple', subtitle: 'Реролл, страховка, доп. слот' },
  'Кастомизация': { icon: '🎭', gradient: 'fire', subtitle: 'Рамки и титулы профиля' },
  'Социальные': { icon: '🤝', gradient: 'teal', subtitle: 'Подарки друзьям' },
};

function SectionButton({ title, onClick }) {
  const meta = SECTION_META[title];
  return (
    <div className={`boost-card boost-card-wide section-button boost-${meta.gradient}`} onClick={onClick}>
      <div className="boost-icon">{meta.icon}</div>
      <div>
        <div className="boost-title">{title}</div>
        <div className="boost-duration">{meta.subtitle}</div>
      </div>
      <i className="ti ti-chevron-right section-button-arrow"></i>
    </div>
  );
}

const FRAME_NAMES = {
  fire: '🔥 Огненная',
  ice: '❄️ Ледяная',
  purple: '💜 Фиолетовая',
  gold: '👑 Золотая',
  egc: '💜 «EGC» (эксклюзивная)',
};

/** «🎒 Мои предметы» — то же, что экран в боте (GamePlatformBot.sendMyItems): рамки, титул, билеты
 *  колеса и активные усиления одним списком, без «нет/нет/нет» по каждому предмету отдельно. */
function MyItemsView({ state, profile, tickets }) {
  const frames = profile?.ownedFrames || [];
  const active = [];
  if (state.xpBoostActive) active.push(`⚡ XP-буст — до ${state.xpBoostUntil}`);
  if (state.excBoostActive) active.push(`⚡ EXC-буст — до ${state.excBoostUntil}`);
  if (profile?.hasPermanentExtraSlot) active.push('📂 Доп. слот квеста — навсегда');
  else if (state.extraSlotActive) active.push(`📂 Доп. слот квеста — до ${state.extraSlotUntil}`);
  if (state.insuranceActive) active.push('🛡️ Страховка провала — активна');
  if (profile?.hasEgcPass) active.push(`⭐ EGC Pass — до ${profile.egcPassActiveUntil}`);

  return (
    <>
      <div className="shop-card">
        <div className="shop-title">🖼️ Рамки аватара</div>
        <p className="shop-desc">
          {frames.length === 0
            ? 'Пока нет ни одной — загляните в «Кастомизацию».'
            : frames.map(k => (FRAME_NAMES[k] || k) + (k === profile?.avatarFrameImage ? ' (надета)' : '')).join(' · ')}
        </p>
      </div>
      <div className="shop-card">
        <div className="shop-title">🏅 Титул</div>
        <p className="shop-desc">
          {profile?.profileTitle || 'не выбран'}
          {profile?.hasPatronTitle ? ' (доступен эксклюзивный «Покровитель EGC»)' : ''}
        </p>
      </div>
      <div className="shop-card">
        <div className="shop-title">🎟️ Билеты колеса фортуны: {tickets ?? 0}</div>
      </div>
      <div className="shop-card">
        <div className="shop-title">Активные усиления</div>
        {active.length === 0
          ? <p className="shop-desc">Сейчас ничего не активно — загляните в разделы «Бусты» и «Квесты».</p>
          : active.map(line => <p key={line} className="shop-desc">{line}</p>)}
      </div>
    </>
  );
}

function PerksView({ expanded, onToggle }) {
  const [state, setState] = useState(null);
  const [frames, setFrames] = useState(null);
  const [profile, setProfile] = useState(null);
  const [error, setError] = useState(null);
  const [prices, setPrices] = useState(STARS_PRICE_FALLBACK);
  const [tickets, setTickets] = useState(0);
  const [activeSection, setActiveSection] = useState(null);

  function reload() {
    setError(null);
    getPerksState().then(setState).catch(() => setError('Не удалось загрузить предметы. Попробуйте ещё раз.'));
    getShopItems().then(items => setFrames(items.filter(i => i.category === 'Кастомизация'))).catch(() => setFrames([]));
    getProfile().then(setProfile).catch(() => {});
    getStarsPrices().then(p => setPrices({ ...STARS_PRICE_FALLBACK, ...p })).catch(() => {});
    getWallet().then(w => setTickets(w.tickets)).catch(() => {});
  }

  useEffect(() => { reload(); }, []);

  if (error) return <div className="page-center error-msg">{error}</div>;
  if (state === null) return <div className="page-center">Загрузка...</div>;

  // Раздел скрывается из списка кнопок целиком, если внутри реально нечего показать — та же логика,
  // что раньше решала, рисовать ли весь блок category-section.
  const sectionHasContent = title => {
    if (title === 'Социальные' || title === 'Мои предметы') return true;
    const cat = PERK_CATEGORIES.find(c => c.title === title);
    const visible = cat.items.filter(item => !item.hideIf || !item.hideIf(state));
    const isCustomization = title === 'Кастомизация';
    const isQuests = title === 'Квесты';
    return visible.length > 0
      || (isCustomization && frames?.length)
      || (isCustomization && !profile?.hasPatronTitle)
      || (isQuests && !profile?.hasPermanentExtraSlot);
  };

  if (activeSection === null) {
    return (
      <>
        <div className="shop-header">
          <div className="shop-balance"><i className="ti ti-coin"></i> {state.coins.toLocaleString()} EXC</div>
          {state.profileTitle && <div className="shop-ratio">🏅 {state.profileTitle}</div>}
        </div>

        <div className="category-section">
          {Object.keys(SECTION_META).filter(sectionHasContent).map(title => (
            <SectionButton key={title} title={title} onClick={() => setActiveSection(title)} />
          ))}
        </div>
      </>
    );
  }

  const cat = PERK_CATEGORIES.find(c => c.title === activeSection);
  const visible = cat ? cat.items.filter(item => !item.hideIf || !item.hideIf(state)) : [];
  const isCustomization = activeSection === 'Кастомизация';
  const isQuests = activeSection === 'Квесты';
  const isSocial = activeSection === 'Социальные';
  const isMyItems = activeSection === 'Мои предметы';

  return (
    <>
      <div className="category-section">
        <div style={{ marginBottom: 14 }}><BackButton label="Назад" onClick={() => setActiveSection(null)} /></div>
        <div className="category-header">{SECTION_META[activeSection].icon} {activeSection}</div>

        {isMyItems ? (
          <MyItemsView state={state} profile={profile} tickets={tickets} />
        ) : isSocial ? (
          <GiftCard expanded={expanded === 'gift'} onToggle={onToggle} />
        ) : (
          <>
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
                ownedFrames={profile?.ownedFrames}
                activeFrame={profile?.avatarFrameImage}
              />
            ))}
            {isCustomization && !(profile?.ownedFrames || []).includes('egc') && (
              <StarsShopCard
                itemType="AVATAR_FRAME"
                icon="👑"
                title="Рамка «EGC»"
                description="Эксклюзивная рамка клуба, навсегда"
                price={prices.AVATAR_FRAME}
                successMessage="✅ Рамка куплена!"
                onPurchased={reload}
                expanded={expanded === 'stars-AVATAR_FRAME'}
                onToggle={onToggle}
              />
            )}
            {isCustomization && !profile?.hasPatronTitle && (
              <StarsShopCard
                itemType="PATRON_TITLE"
                icon="💎"
                title="Титул «Покровитель EGC»"
                description="Эксклюзивный статус, недоступен за EXC — виден всем в клубе"
                price={prices.PATRON_TITLE}
                successMessage="✅ Титул куплен и надет!"
                onPurchased={reload}
                expanded={expanded === 'stars-PATRON_TITLE'}
                onToggle={onToggle}
              />
            )}
            {isQuests && !profile?.hasPermanentExtraSlot && (
              <StarsShopCard
                itemType="PERMANENT_SLOT"
                icon="📂"
                title="Доп. слот квеста — навсегда"
                description="На 1 активный квест больше постоянно — обычно доступно только на 48ч за EXC"
                price={prices.PERMANENT_SLOT}
                successMessage="✅ Слот куплен навсегда!"
                onPurchased={reload}
                expanded={expanded === 'stars-PERMANENT_SLOT'}
                onToggle={onToggle}
              />
            )}
          </>
        )}
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
    <div className="category-section" style={{ padding: '12px 16px', display: 'flex', flexDirection: 'column', gap: 12 }}>
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
