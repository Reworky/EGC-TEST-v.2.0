import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getWallet, claimDailyBonus, getTonQuote, withdrawRub, withdrawTon, getWithdrawals, cancelReward, getReferrals } from '../api/client';
import BorderBeamCard from '../components/BorderBeamCard';
import ShimmerButton from '../components/ShimmerButton';
import AnimatedNumber from '../components/AnimatedNumber';
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

function BalanceView({ wallet, onChanged }) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);
  const [refData, setRefData] = useState(null);
  const [refCopied, setRefCopied] = useState(false);
  const playParticles = useParticles();

  useEffect(() => {
    getReferrals().then(setRefData).catch(() => {});
  }, []);

  function copyRefLink() {
    if (!refData?.referralLink) return;
    const markCopied = () => { setRefCopied(true); setTimeout(() => setRefCopied(false), 2000); };
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(refData.referralLink).then(markCopied, () => markCopied());
    } else {
      markCopied();
    }
  }

  function shareRefLink() {
    if (!refData?.referralLink) return;
    const url = `https://t.me/share/url?url=${encodeURIComponent(refData.referralLink)}&text=${encodeURIComponent('Присоединяйся к EXPERIENCE GAMING CLUB!')}`;
    window.Telegram?.WebApp?.openTelegramLink?.(url) || window.open(url, '_blank');
  }

  async function handleClaim() {
    setBusy(true);
    setMessage(null);
    try {
      const res = await claimDailyBonus();
      if (res.success) {
        let msg = `+${res.totalExc} EXC · Серия: ${res.streakDays} дн.`;
        if (res.milestoneText) msg = `${res.milestoneText} ${msg}`;
        setMessage(msg);
        playParticles?.('streakBonus', 3000);
        onChanged();
      } else {
        setMessage(res.message);
      }
    } finally {
      setBusy(false);
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

      <div className="ref-stats-grid">
        <div className="stat-card">
          <div className="stat-icon">💠</div>
          <div className="stat-value">+{wallet.excBonusPercent}%</div>
          <div className="stat-label">Бонус к EXC</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon">🎟️</div>
          <div className="stat-value">{wallet.tickets}</div>
          <div className="stat-label">Билеты сезона</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon"><i className="ti ti-star"></i></div>
          <div className="stat-value"><AnimatedNumber value={wallet.xp} flashColor="#7B68EE" /></div>
          <div className="stat-label">Общий XP</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon">📈</div>
          <div className="stat-value">{wallet.weeklyXp.toLocaleString()}</div>
          <div className="stat-label">XP за неделю</div>
        </div>
      </div>

      <div className="ref-progress-card">
        <div className="ref-progress-label">📊 Состояние фонда клуба: {wallet.healthRatioPercent}%</div>
        <div className="ref-progress-label">
          💸 Лимит вывода: {wallet.remainingWithdrawalLimit.toLocaleString()} / {wallet.monthlyWithdrawalLimit.toLocaleString()} EXC в этом месяце
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
        {message && <div className="quest-message">{message}</div>}
      </div>

      {refData?.referralLink && (
        <div className="ref-link-card">
          <div className="ref-link-label">🤝 Пригласи друга — получи бонус</div>
          <p className="shop-desc">За каждого приглашённого игрока ты получаешь 10% от его заработка в первые 30 дней.</p>
          <div className="ref-link-value">{refData.referralLink}</div>
          <div style={{ display: 'flex', gap: '8px', marginTop: '10px' }}>
            <button className="quest-btn" style={{ flex: 1 }} onClick={copyRefLink}>
              {refCopied ? '✅ Скопировано' : '📋 Скопировать'}
            </button>
            <button className="quest-btn" style={{ flex: 1 }} onClick={shareRefLink}>
              📤 Поделиться
            </button>
          </div>
        </div>
      )}
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
      <input type="number" className="quest-text-input" placeholder="Сумма в EXC" value={amount} onChange={e => setAmount(e.target.value)} />
      <input type="text" className="quest-text-input" placeholder="Банк и номер телефона (СБП)" value={requisites} onChange={e => setRequisites(e.target.value)} style={{ marginTop: 8 }} />
      <button className="quest-btn" disabled={busy} onClick={handleSubmit}>{busy ? 'Секунду...' : 'Отправить заявку'}</button>
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
      <button className="quest-btn" disabled={busy} onClick={handleSubmit}>{busy ? 'Секунду...' : 'Отправить заявку'}</button>
      {message && <div className="quest-message">{message}</div>}
    </div>
  );
}

function WithdrawView({ wallet, onChanged }) {
  const [method, setMethod] = useState('rub');

  return (
    <>
      <div className="view-toggle" style={{ padding: '10px 16px 12px' }}>
        <button className={`view-tab ${method === 'rub' ? 'active' : ''}`} onClick={() => setMethod('rub')}>💸 Рубли</button>
        <button className={`view-tab ${method === 'ton' ? 'active' : ''}`} onClick={() => setMethod('ton')}>💎 GRAM (TON)</button>
      </div>
      {method === 'rub' ? <WithdrawRubForm wallet={wallet} onDone={onChanged} /> : <WithdrawTonForm wallet={wallet} onDone={onChanged} />}
    </>
  );
}

function MyWithdrawalsView() {
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
    } finally {
      setBusyId(null);
    }
  }

  if (error) return <div className="page-center error-msg">{error}</div>;
  if (items === null) return <div className="page-center">Загрузка...</div>;
  if (items.length === 0) return <div className="page-center">📭 У вас ещё нет заявок на вывод EXC.</div>;

  return (
    <div className="category-section">
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

  function reload() {
    getWallet().then(setWallet).catch(() => setError('Не удалось загрузить кошелёк. Попробуйте ещё раз.'));
  }

  useEffect(() => { reload(); }, []);

  return (
    <div className="quests-page shop-page">
      <Link to="/profile" className="ref-back" style={{ display: 'block', padding: '16px 16px 0' }}>← Профиль</Link>

      <div className="view-toggle">
        <button className={`view-tab ${view === 'balance' ? 'active' : ''}`} onClick={() => setView('balance')}>Баланс</button>
        <button className={`view-tab ${view === 'withdraw' ? 'active' : ''}`} onClick={() => setView('withdraw')}>Вывод</button>
        <button className={`view-tab ${view === 'mine' ? 'active' : ''}`} onClick={() => setView('mine')}>Мои заявки</button>
      </div>

      {error && <div className="page-center error-msg">{error}</div>}
      {!error && view !== 'mine' && wallet === null && <div className="page-center">Загрузка...</div>}
      {!error && view === 'balance' && wallet && <BalanceView wallet={wallet} onChanged={reload} />}
      {!error && view === 'withdraw' && wallet && <WithdrawView wallet={wallet} onChanged={reload} />}
      {view === 'mine' && <MyWithdrawalsView />}
    </div>
  );
}
