import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { getWallet, claimDailyBonus, openChest, getTonQuote, withdrawRub, withdrawTon, getWithdrawals, cancelReward, confirmPhone, invalidateCache, getStarsWithdrawItems, purchaseItem, getStarsInvoiceLink, getStarsPrices } from '../api/client';
import { openStarsInvoice } from '../utils/stars';
import chestRegularImg from '../assets/chests/regular.png';
import chestPremiumImg from '../assets/chests/premium.png';
import { RANKS_DATA, getLevelFromXp } from '../data/ranks';
import BackButton from '../components/BackButton';
import BorderBeamCard from '../components/BorderBeamCard';
import ShimmerButton from '../components/ShimmerButton';
import AnimatedNumber from '../components/AnimatedNumber';
import AdRewardCard from '../components/AdRewardCard';
import AdBanner from '../components/AdBanner';
import { useParticles } from '../components/ParticlesContext';
import './QuestsPage.css';
import './ShopPage.css';
import './ReferralsPage.css';
import './WalletPage.css';

const STATUS_LABELS = {
  PENDING: <><i className="ti ti-clock"></i> Ожидает</>,
  IN_PROGRESS: <><i className="ti ti-clock"></i> В обработке</>,
  APPROVED: <><i className="ti ti-circle-check"></i> Выплачено</>,
  REJECTED: <><i className="ti ti-circle-x"></i> Отклонено</>,
  CANCELLED: <><i className="ti ti-circle-x"></i> Отменено</>,
};

// Запасной вариант на случай недоступности /api/stars/prices — реальная цена подтягивается через
// getStarsPrices() в BalanceView (см. chestRerollPrice), не отсюда.
const CHEST_REROLL_STARS_PRICE = 15;

const CHEST_PRIZES = [
  { label: '🎉 500 EXC (джекпот)', chance: '2%' },
  { label: '🎟️ Билет колеса фортуны', chance: '8%' },
  { label: '✨ 150-250 EXC', chance: '25%' },
  { label: '🪙 75-125 EXC', chance: '65%' },
];

const CHEST_REROLL_PRIZES = [
  { label: '🎉 2 000 EXC (джекпот)', chance: '5%' },
  { label: '🎟️ 2 билета колеса фортуны', chance: '15%' },
  { label: '✨ 400-600 EXC', chance: '40%' },
  { label: '🪙 200-350 EXC', chance: '40%' },
];

function ChestPrizesModal({ price, onClose }) {
  useEffect(() => {
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prevOverflow; };
  }, []);

  return (
    <div className="fund-modal-overlay" onClick={onClose} style={{ backdropFilter: 'none', background: 'rgba(8,8,14,0.88)', overscrollBehavior: 'contain' }}>
      <div
        className="fund-modal"
        onClick={e => e.stopPropagation()}
        style={{ maxWidth: 340, maxHeight: '80vh', overflowY: 'auto', WebkitOverflowScrolling: 'touch', overscrollBehavior: 'contain' }}
      >
        <div className="fund-modal-title">📋 Призы сундука дня</div>
        <p className="fund-modal-text" style={{ opacity: 0.6, marginTop: 0 }}>Бесплатно (раз в сутки):</p>
        {CHEST_PRIZES.map(p => (
          <p key={p.label} className="fund-modal-text">{p.label} — {p.chance}</p>
        ))}
        <p className="fund-modal-text" style={{ opacity: 0.6, marginTop: 12 }}>Реролл за {price}⭐ (можно сколько угодно раз):</p>
        {CHEST_REROLL_PRIZES.map(p => (
          <p key={p.label} className="fund-modal-text">{p.label} — {p.chance}</p>
        ))}
        <button className="fund-modal-close" onClick={onClose}>Понятно</button>
      </div>
    </div>
  );
}

function RanksModal({ currentXp, onClose }) {
  const currentLevel = getLevelFromXp(currentXp);

  // Блокируем скролл фоновой страницы, пока открыта модалка, и убираем
  // backdrop-filter (см. тот же фикс в ProfilePage.jsx/RanksModal) — на iOS
  // WebView блюр поверх фикс-оверлея дёргает скролл списка внутри.
  useEffect(() => {
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prevOverflow; };
  }, []);

  return (
    <div className="fund-modal-overlay" onClick={onClose} style={{ backdropFilter: 'none', background: 'rgba(8,8,14,0.88)', overscrollBehavior: 'contain' }}>
      <div
        className="fund-modal"
        onClick={e => e.stopPropagation()}
        style={{ maxWidth: 340, maxHeight: '80vh', overflowY: 'auto', WebkitOverflowScrolling: 'touch', overscrollBehavior: 'contain' }}
      >
        <div className="fund-modal-title">📊 Все ранги</div>
        {RANKS_DATA.map(r => (
          <p key={r.number} className="fund-modal-text" style={r.number === currentLevel ? { color: '#a78bfa', fontWeight: 600 } : undefined}>
            {r.number === currentLevel ? '▶️ ' : ''}{r.number}. {r.name} — от {r.minXp.toLocaleString('ru-RU')} XP,
            +{r.bonus}% к наградам, лимит {r.limit.toLocaleString('ru-RU')} EXC/мес
          </p>
        ))}
        <p className="fund-modal-text" style={{ opacity: 0.6 }}>Лимит вывода растёт вместе с общим опытом и не сбрасывается каждую неделю.</p>
        <button className="fund-modal-close" onClick={onClose}>Понятно</button>
      </div>
    </div>
  );
}

function BalanceView({ wallet, onChanged, highlightChest }) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);
  const [messageOk, setMessageOk] = useState(false);
  const [chestBusy, setChestBusy] = useState(false);
  const [chestMessage, setChestMessage] = useState(null);
  const [chestMessageOk, setChestMessageOk] = useState(false);
  const [rerollBusy, setRerollBusy] = useState(false);
  const [showRanks, setShowRanks] = useState(false);
  const [showChestPrizes, setShowChestPrizes] = useState(false);
  const [chestPulse, setChestPulse] = useState(false);
  const [chestRerollPrice, setChestRerollPrice] = useState(CHEST_REROLL_STARS_PRICE);
  const chestRef = useRef(null);
  const playParticles = useParticles();

  // Переход из бота по ссылке "Сундук дня" (?section=chest) — скроллим к карточке и подсвечиваем
  // её, вместо того чтобы открывать сундук прямо в чате бота (запрошено 2026-09-15).
  useEffect(() => {
    if (!highlightChest || !chestRef.current) return;
    chestRef.current.scrollIntoView({ behavior: 'smooth', block: 'center' });
    setChestPulse(true);
    const t = setTimeout(() => setChestPulse(false), 2200);
    return () => clearTimeout(t);
  }, [highlightChest]);

  // Реальная цена — из каталога STARS_ITEMS на бэкенде (см. StarsController "/api/stars/prices"),
  // CHEST_REROLL_STARS_PRICE ниже — только запасной вариант на случай недоступности запроса, чтобы
  // цена в мини-аппе не могла молча разъехаться с тем, что реально спишет инвойс (найдено при аудите
  // Stars-покупок, 2026-09-19).
  useEffect(() => {
    getStarsPrices().then(p => { if (p.CHEST_REROLL) setChestRerollPrice(p.CHEST_REROLL); }).catch(() => {});
  }, []);

  async function handleClaim() {
    setBusy(true);
    setMessage(null);
    setMessageOk(false);
    try {
      const res = await claimDailyBonus();
      if (res.success) {
        let msg = `+${res.totalExc} EXC\nСерия: ${res.streakDays} дн.`;
        if (res.milestoneText) msg = `${res.milestoneText}\n${msg}`;
        setMessage(msg);
        setMessageOk(true);
        playParticles?.('streakBonus', 3000);
        onChanged();
      } else {
        setMessage(res.message);
      }
    } finally {
      setBusy(false);
    }
  }

  async function handleOpenChest() {
    setChestBusy(true);
    setChestMessage(null);
    setChestMessageOk(false);
    try {
      const res = await openChest();
      if (res.success) {
        let msg = res.prizeLabel;
        if (res.exc > 0) msg += `\n+${res.exc} EXC`;
        if (res.tickets > 0) msg += `\n+${res.tickets} 🎟️`;
        setChestMessage(msg);
        setChestMessageOk(true);
        playParticles?.('streakBonus', 3000);
        onChanged();
      } else {
        setChestMessage(res.message);
      }
    } finally {
      setChestBusy(false);
    }
  }

  async function handleBuyReroll() {
    setRerollBusy(true);
    setChestMessage(null);
    setChestMessageOk(false);
    try {
      const invoice = await getStarsInvoiceLink('CHEST_REROLL');
      if (!invoice.success) {
        setChestMessage(invoice.message);
        return;
      }
      const status = await openStarsInvoice(invoice.url);
      if (status === 'failed') {
        setChestMessage('Платёж не прошёл. Попробуйте ещё раз.');
        return;
      }
      if (status !== 'paid') return;
      // Приз начисляется на сервере асинхронно (successful_payment от Telegram) — даём секунду
      // и сравниваем баланс до/после, чтобы показать реальный результат, как в боте.
      await new Promise(r => setTimeout(r, 1200));
      invalidateCache('wallet');
      const fresh = await getWallet();
      let msg = '✅ Сундук открыт!';
      const dCoins = fresh.coins - wallet.coins;
      const dTickets = fresh.tickets - wallet.tickets;
      if (dCoins > 0) msg += `\n+${dCoins} EXC`;
      if (dTickets > 0) msg += `\n+${dTickets} 🎟️`;
      setChestMessage(msg);
      setChestMessageOk(true);
      playParticles?.('streakBonus', 3000);
      onChanged();
    } catch (e) {
      setChestMessage(e.message || 'Не удалось открыть оплату.');
    } finally {
      setRerollBusy(false);
    }
  }

  return (
    <>
      <BorderBeamCard style={{ margin: '12px 16px', textAlign: 'center' }}>
        <div style={{ fontSize: 12, color: 'rgba(255,255,255,0.5)', marginBottom: 4 }}>
          <i className="ti ti-wallet" /> Баланс клуба
        </div>
        <div style={{ fontSize: 30, fontWeight: 600, color: '#F5A623' }}>
          <i className="ti ti-coin" style={{ marginRight: 4 }} />
          <AnimatedNumber value={wallet.coins} flashColor="#F5A623" /> EXC
        </div>
        <div style={{ fontSize: 12, color: 'rgba(255,255,255,0.4)', marginTop: 4 }}>
          ≈ {+(wallet.coins * wallet.healthRatioPercent / 100 / 100).toFixed(1)} ₽ · фонд {wallet.healthRatioPercent}%
        </div>
      </BorderBeamCard>

      <div className="w-stats-grid">
        <div className="w-stat-card">
          <div className="w-stat-accent" style={{ background: 'linear-gradient(90deg,#6366f1,#818cf8)' }} />
          <div className="w-stat-label">Бонус к EXC</div>
          <div className="w-stat-val" style={{ color: '#818cf8' }}>+{wallet.excBonusPercent}%</div>
          <svg className="w-stat-illus" width="48" height="44" viewBox="0 0 48 44" fill="none">
            <circle cx="24" cy="22" r="14" fill="#6366f1" opacity=".18"/>
            <path d="M24 10l3.5 7 7.5 1-5.5 5.5 1.3 7.5L24 27.5l-6.8 3.5 1.3-7.5L13 18l7.5-1z" fill="#818cf8" opacity=".7"/>
          </svg>
        </div>
        <div className="w-stat-card">
          <div className="w-stat-accent" style={{ background: 'linear-gradient(90deg,#f59e0b,#fbbf24)' }} />
          <div className="w-stat-label">Билеты сезона</div>
          <div className="w-stat-val" style={{ color: '#fbbf24' }}>{wallet.tickets}</div>
          <svg className="w-stat-illus" width="48" height="44" viewBox="0 0 48 44" fill="none">
            <rect x="8" y="14" width="32" height="18" rx="5" fill="#92400e" opacity=".4"/>
            <rect x="12" y="18" width="24" height="10" rx="3" fill="#fbbf24" opacity=".5"/>
            <circle cx="12" cy="22" r="3" fill="#f59e0b" opacity=".8"/>
            <circle cx="36" cy="22" r="3" fill="#f59e0b" opacity=".8"/>
          </svg>
        </div>
        <div className="w-stat-card">
          <div className="w-stat-accent" style={{ background: 'linear-gradient(90deg,#8b5cf6,#a78bfa)' }} />
          <div className="w-stat-label">Общий XP</div>
          <div className="w-stat-val" style={{ color: '#a78bfa' }}><AnimatedNumber value={wallet.xp} flashColor="#a78bfa" /></div>
          <svg className="w-stat-illus" width="48" height="44" viewBox="0 0 48 44" fill="none">
            <circle cx="24" cy="24" r="13" fill="#6d28d9" opacity=".2"/>
            <path d="M16 30l5-8 4 5 3-5 4 8" stroke="#a78bfa" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" fill="none"/>
          </svg>
        </div>
        <div className="w-stat-card">
          <div className="w-stat-accent" style={{ background: 'linear-gradient(90deg,#10b981,#34d399)' }} />
          <div className="w-stat-label">XP за неделю</div>
          <div className="w-stat-val" style={{ color: '#34d399' }}>{wallet.weeklyXp.toLocaleString()}</div>
          <svg className="w-stat-illus" width="48" height="44" viewBox="0 0 48 44" fill="none">
            <rect x="10" y="28" width="6" height="8" rx="2" fill="#34d399" opacity=".5"/>
            <rect x="20" y="20" width="6" height="16" rx="2" fill="#34d399" opacity=".6"/>
            <rect x="30" y="14" width="6" height="22" rx="2" fill="#34d399" opacity=".8"/>
          </svg>
        </div>
      </div>

      <div className="ref-progress-card">
        <div className="ref-progress-label">📊 Состояние фонда клуба: {wallet.healthRatioPercent}%</div>
        <div className="ref-progress-label">
          💸 Лимит вывода: {wallet.remainingWithdrawalLimit.toLocaleString()} / {wallet.monthlyWithdrawalLimit.toLocaleString()} EXC в этом месяце
        </div>
        <div className="ref-progress-label" style={{ color: 'rgba(167,139,250,0.85)', cursor: 'pointer' }} onClick={() => setShowRanks(true)}>
          📊 Лимиты по всем рангам →
        </div>
        {wallet.fixedRubBalance > 0 && (
          <div className="ref-progress-label">
            <i className="ti ti-circle-check"></i> Гарантировано к выводу: {wallet.fixedRubBalance.toLocaleString()} ₽
          </div>
        )}
      </div>

      <div className="ref-link-card">
        <div className="ref-link-label">Ежедневный бонус</div>
        {wallet.dailyBonusAvailable ? (
          <>
            <p className="shop-desc"><i className="ti ti-flame"></i> Серия: {wallet.streakDays} дн. · Следующий бонус: +{wallet.nextDailyBonusExc} EXC</p>
            <ShimmerButton disabled={busy} onClick={handleClaim}>
              {busy ? 'Секунду...' : <><i className="ti ti-gift" style={{ marginRight: 6 }} /> Забрать бонус</>}
            </ShimmerButton>
          </>
        ) : (
          <p className="shop-desc"><i className="ti ti-circle-check"></i> Бонус за сегодня уже получен. Серия: {wallet.streakDays} дн. Возвращайся завтра за +{wallet.nextDailyBonusExc} EXC.</p>
        )}
        {message && (
          <div className={messageOk ? 'reward-pop' : 'quest-message'}>
            {messageOk && '🎉 '}{message}
          </div>
        )}
      </div>

      <div
        ref={chestRef}
        className={`ref-link-card${chestPulse ? ' chest-pulse' : ''}`}
        style={{ marginTop: 12 }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          <img src={chestRegularImg} alt="" style={{ width: 72, height: 72, objectFit: 'contain', flexShrink: 0, opacity: wallet.chestAvailable ? 1 : 0.4, transition: 'opacity 0.2s' }} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="ref-link-label">Сундук дня</div>
            {wallet.chestAvailable ? (
              <p className="shop-desc"><i className="ti ti-sparkles"></i> Раз в сутки — случайный приз: EXC, билет колеса фортуны или джекпот.</p>
            ) : (
              <p className="shop-desc"><i className="ti ti-circle-check"></i> Сундук на сегодня открыт. Возвращайся завтра за новым призом.</p>
            )}
          </div>
        </div>
        {wallet.chestAvailable && (
          <ShimmerButton disabled={chestBusy} onClick={handleOpenChest} style={{ marginTop: 10 }}>
            {chestBusy ? 'Открываем...' : <><i className="ti ti-gift" style={{ marginRight: 6 }} /> Открыть сундук</>}
          </ShimmerButton>
        )}

        <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginTop: 16, paddingTop: 14, borderTop: '1px solid rgba(255,255,255,0.08)' }}>
          <img src={chestPremiumImg} alt="" style={{ width: 72, height: 72, objectFit: 'contain', flexShrink: 0 }} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="ref-link-label">Улучшенный сундук</div>
            <p className="shop-desc"><i className="ti ti-sparkles"></i> Призы заметно щедрее бесплатного — за Stars, можно сколько угодно раз.</p>
          </div>
        </div>
        <ShimmerButton disabled={rerollBusy} onClick={handleBuyReroll} style={{ marginTop: 10 }}>
          {rerollBusy ? 'Секунду...' : <><i className="ti ti-sparkles" style={{ marginRight: 6 }} /> Купить — {chestRerollPrice} ⭐</>}
        </ShimmerButton>
        <p className="ref-progress-label" style={{ color: 'rgba(167,139,250,0.85)', cursor: 'pointer', marginTop: 8 }} onClick={() => setShowChestPrizes(true)}>
          📋 Все призы →
        </p>

        {chestMessage && (
          <div className={chestMessageOk ? 'reward-pop' : 'quest-message'} style={{ marginTop: 10 }}>
            {chestMessageOk && '🎉 '}{chestMessage}
          </div>
        )}
      </div>

      <div className="category-section" style={{ marginTop: 12 }}>
        <div className="ref-link-label" style={{ marginBottom: 10 }}>🎬 Забери халявные EXC</div>
        <AdRewardCard />
        <div style={{ marginTop: 12 }}>
          <AdBanner
            img="https://aflink.ru/b/vzfiwgube7e999950e9542f9f2178b/"
            link="https://dhwnh.com/g/vzfiwgube7e999950e9542f9f2178b/?i=4&erid=2bL9aMPo2e49hMef4piV5ABPED"
            alt="Puzzle Movies"
            erid="2bL9aMPo2e49hMef4piV5ABPED"
          />
        </div>
      </div>

      {showRanks && <RanksModal currentXp={wallet.xp} onClose={() => setShowRanks(false)} />}
      {showChestPrizes && <ChestPrizesModal price={chestRerollPrice} onClose={() => setShowChestPrizes(false)} />}
    </>
  );
}

function WithdrawRubForm({ wallet, onDone }) {
  const [amount, setAmount] = useState('');
  const [requisites, setRequisites] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);

  async function handleSubmit() {
    const amt = parseInt(amount, 10);
    if (!amt) {
      setMessage('Введите сумму числом.');
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const res = await withdrawRub(amt, requisites);
      setMessage(res.message);
      if (res.success) {
        setAmount('');
        setRequisites('');
        onDone();
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="ref-link-card">
      <div className="ref-link-label">Вывод в рублях (СБП)</div>
      <p className="shop-desc">Минимум 5 000 EXC. Курс: 1 000 EXC = {+(10 * wallet.healthRatioPercent / 100).toFixed(1)} ₽ (фонд {wallet.healthRatioPercent}%). Доступно: {wallet.remainingWithdrawalLimit.toLocaleString()} EXC.</p>
      <input type="number" className="quest-text-input" placeholder="Сумма в EXC" value={amount} onChange={e => setAmount(e.target.value)} style={{ marginTop: 16 }} />
      <input type="text" className="quest-text-input" placeholder="Банк и номер телефона (СБП)" value={requisites} onChange={e => setRequisites(e.target.value)} style={{ marginTop: 8 }} />
      <button className="quest-btn" disabled={busy} onClick={handleSubmit} style={{ marginTop: 16 }}>{busy ? 'Секунду...' : 'Отправить заявку'}</button>
      {message && <div className="quest-message">{message}</div>}
    </div>
  );
}

function WithdrawTonForm({ wallet, onDone }) {
  const [amount, setAmount] = useState('');
  const [wallet_, setWallet] = useState('');
  const [quote, setQuote] = useState(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);

  useEffect(() => {
    const amt = parseInt(amount, 10);
    if (!amt || amt < 1) {
      setQuote(null);
      return;
    }
    const timer = setTimeout(() => {
      getTonQuote(amt).then(setQuote).catch(() => setQuote(null));
    }, 400);
    return () => clearTimeout(timer);
  }, [amount]);

  async function handleSubmit() {
    const amt = parseInt(amount, 10);
    if (!amt) {
      setMessage('Введите сумму числом.');
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const res = await withdrawTon(amt, wallet_);
      setMessage(res.message);
      if (res.success) {
        setAmount('');
        setWallet('');
        setQuote(null);
        onDone();
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="ref-link-card">
      <div className="ref-link-label">Вывод в GRAM (TON)</div>
      <p className="shop-desc">Минимум 5 000 EXC. Доступно: {wallet.remainingWithdrawalLimit.toLocaleString()} EXC.</p>
      <input type="number" className="quest-text-input" placeholder="Сумма в EXC" value={amount} onChange={e => setAmount(e.target.value)} />
      {quote && (
        <div className="shop-status" style={{ marginTop: 6 }}>
          ≈ {quote.rubles.toLocaleString()} ₽ → ≈ {quote.tonAmount} GRAM
          {quote.usingFallback ? ' (курс приблизительный)' : ` (курс 1 GRAM ≈ ${quote.tonRate} ₽)`}
        </div>
      )}
      <input type="text" className="quest-text-input" placeholder="Адрес TON-кошелька (UQ... / EQ...)" value={wallet_} onChange={e => setWallet(e.target.value)} style={{ marginTop: 8 }} />
      <a className="quest-btn quest-btn-secondary" style={{ display: 'block', textAlign: 'center', textDecoration: 'none', marginTop: 8 }}
         href="https://t.me/wallet/start?startapp=ref-3-PaQlujnvUGU" target="_blank" rel="noreferrer">
        💎 Открыть Telegram Wallet
      </a>
      <button className="quest-btn" disabled={busy} onClick={handleSubmit} style={{ marginTop: 16 }}>{busy ? 'Секунду...' : 'Отправить заявку'}</button>
      {message && <div className="quest-message">{message}</div>}
    </div>
  );
}

function WithdrawStarsForm({ wallet, onDone }) {
  const [items, setItems] = useState(null);
  const [selectedId, setSelectedId] = useState(null);
  const [username, setUsername] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);

  useEffect(() => { getStarsWithdrawItems().then(setItems).catch(() => setItems([])); }, []);

  function denom(title) { return title.replace(/.*- /, ''); }

  async function handleSubmit() {
    if (!selectedId) { setMessage('Выберите номинал.'); return; }
    if (!username.trim()) { setMessage('Введите юзернейм получателя.'); return; }
    setBusy(true);
    setMessage(null);
    try {
      const res = await purchaseItem(selectedId, username.trim().replace(/^@/, ''));
      setMessage(res.message);
      if (res.success) {
        setSelectedId(null);
        setUsername('');
        getStarsWithdrawItems().then(setItems).catch(() => {});
        onDone();
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="ref-link-card">
      <div className="ref-link-label">Вывод в звёздах Telegram</div>
      <p className="shop-desc">Доступно: {wallet.remainingWithdrawalLimit.toLocaleString()} EXC. Отправляется вручную по юзернейму, в течение 24 ч.</p>
      {items === null ? (
        <p className="shop-desc">Загрузка...</p>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, marginTop: 12 }}>
          {items.map(item => (
            <button
              key={item.id}
              className={`quest-btn ${selectedId === item.id ? '' : 'quest-btn-secondary'}`}
              disabled={item.locked}
              onClick={() => setSelectedId(item.id)}
              style={{ fontSize: 12, lineHeight: 1.5, padding: '8px 6px', opacity: item.locked ? 0.5 : 1 }}
            >
              {denom(item.title)}<br />{item.effectivePrice.toLocaleString()} EXC
            </button>
          ))}
        </div>
      )}
      <input type="text" className="quest-text-input" placeholder="Юзернейм получателя (без @)" value={username} onChange={e => setUsername(e.target.value)} style={{ marginTop: 12 }} />
      <button className="quest-btn" disabled={busy} onClick={handleSubmit} style={{ marginTop: 16 }}>{busy ? 'Секунду...' : 'Отправить заявку'}</button>
      {message && <div className="quest-message">{message}</div>}
    </div>
  );
}

function PhoneGate({ onConfirmed }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  function handleRequest() {
    const tg = window.Telegram?.WebApp;
    if (!tg?.requestContact) {
      setError('Обновите Telegram до версии 6.9 или выше.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      tg.requestContact((ok, event) => {
        if (!ok) {
          setBusy(false);
          setError('Вы отказались поделиться номером. Подтверждение телефона обязательно для вывода.');
          return;
        }
        const phone = event?.responseUnsafe?.contact?.phone_number;
        if (!phone) {
          setBusy(false);
          setError('Не удалось получить номер. Попробуйте ещё раз.');
          return;
        }
        confirmPhone(phone)
          .then(res => {
            if (res.success) onConfirmed();
            else setError(res.message);
          })
          .catch(() => setError('Ошибка сети. Попробуйте ещё раз.'))
          .finally(() => setBusy(false));
      });
    } catch (e) {
      setBusy(false);
      setError('Не удалось запросить номер. Попробуйте ещё раз.');
    }
  }

  return (
    <div className="ref-link-card" style={{ margin: '16px', textAlign: 'center' }}>
      <div style={{ fontSize: 40, marginBottom: 12 }}>📱</div>
      <div className="ref-link-label">Подтверждение телефона</div>
      <p className="shop-desc">
        Для вывода средств необходимо один раз подтвердить ваш номер телефона через Telegram. Это защищает от мультиаккаунтов.
      </p>
      <button className="quest-btn" disabled={busy} onClick={handleRequest}>
        {busy ? 'Ожидание Telegram...' : '📲 Подтвердить номер'}
      </button>
      {error && <div className="quest-message" style={{ marginTop: 10 }}>{error}</div>}
    </div>
  );
}

function WithdrawView({ wallet, onChanged }) {
  const [method, setMethod] = useState('rub');
  const [phoneConfirmed, setPhoneConfirmed] = useState(wallet.phoneConfirmed);

  if (!phoneConfirmed) {
    return <PhoneGate onConfirmed={() => { setPhoneConfirmed(true); onChanged(); }} />;
  }

  return (
    <>
      <div className="view-toggle" style={{ padding: '10px 16px 12px' }}>
        <button className={`view-tab ${method === 'rub' ? 'active' : ''}`} onClick={() => setMethod('rub')}>💸 Рубли</button>
        <button className={`view-tab ${method === 'ton' ? 'active' : ''}`} onClick={() => setMethod('ton')}>💎 GRAM (TON)</button>
        <button className={`view-tab ${method === 'stars' ? 'active' : ''}`} onClick={() => setMethod('stars')}>⭐ Stars</button>
      </div>
      {method === 'rub' && <WithdrawRubForm wallet={wallet} onDone={onChanged} />}
      {method === 'ton' && <WithdrawTonForm wallet={wallet} onDone={onChanged} />}
      {method === 'stars' && <WithdrawStarsForm wallet={wallet} onDone={onChanged} />}
    </>
  );
}

function MyWithdrawalsView({ onWalletChanged }) {
  const [items, setItems] = useState(null);
  const [error, setError] = useState(null);
  const [busyId, setBusyId] = useState(null);

  function reload() {
    setError(null);
    getWithdrawals().then(setItems).catch(() => setError('Не удалось загрузить заявки. Попробуйте ещё раз.'));
  }

  useEffect(() => { reload(); }, []);

  async function handleCancel(id) {
    setBusyId(id);
    try {
      await cancelReward(id);
      reload();
      onWalletChanged?.();
    } finally {
      setBusyId(null);
    }
  }

  if (error) return <div className="page-center error-msg">{error}</div>;
  if (items === null) return <div className="page-center">Загрузка...</div>;
  if (items.length === 0) return <div className="page-center">📭 У вас ещё нет заявок на вывод EXC.</div>;

  return (
    <div className="category-section" style={{ padding: '12px 16px', display: 'flex', flexDirection: 'column', gap: 12 }}>
      {items.map(r => (
        <div key={r.id} className="shop-card">
          <div className="shop-top">
            <div className="shop-title">В-{r.displayId} · {r.method}</div>
            <div className="shop-price">{r.amountExc.toLocaleString()} EXC</div>
          </div>
          <div className="shop-meta">
            <span>{STATUS_LABELS[r.status] || r.status}</span>
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

export default function WalletPage() {
  const [view, setView] = useState('balance');
  const [wallet, setWallet] = useState(null);
  const [error, setError] = useState(null);
  const [searchParams] = useSearchParams();
  const highlightChest = searchParams.get('section') === 'chest';

  function reload() {
    getWallet().then(setWallet).catch(() => setError('Не удалось загрузить кошелёк. Попробуйте ещё раз.'));
  }

  function switchTab(newView) {
    if (newView === 'withdraw' || newView === 'balance') {
      invalidateCache('wallet');
      reload();
    }
    setView(newView);
  }

  useEffect(() => { reload(); }, []);

  return (
    <div className="quests-page shop-page">
      <div style={{ padding: '16px 16px 16px' }}><BackButton to="/profile" label="Профиль" /></div>

      <div className="view-toggle">
        <button className={`view-tab ${view === 'balance' ? 'active' : ''}`} onClick={() => switchTab('balance')}>Баланс</button>
        <button className={`view-tab ${view === 'withdraw' ? 'active' : ''}`} onClick={() => switchTab('withdraw')}>Вывод</button>
        <button className={`view-tab ${view === 'mine' ? 'active' : ''}`} onClick={() => switchTab('mine')}>Мои заявки</button>
      </div>

      {error && <div className="page-center error-msg">{error}</div>}
      {!error && view !== 'mine' && wallet === null && <div className="page-center">Загрузка...</div>}
      {!error && view === 'balance' && wallet && <BalanceView wallet={wallet} onChanged={reload} highlightChest={highlightChest} />}
      {!error && view === 'withdraw' && wallet && <WithdrawView wallet={wallet} onChanged={reload} />}
      {view === 'mine' && <MyWithdrawalsView onWalletChanged={reload} />}
    </div>
  );
}
