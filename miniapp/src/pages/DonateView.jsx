import { useEffect, useState } from 'react';
import { getDonateCatalog, donatePurchase } from '../api/client';
import { openStarsInvoice } from '../utils/stars';
import BackButton from '../components/BackButton';
import './QuestsPage.css';
import './ShopPage.css';

// Донат по играм (Купикод) — зеркало флоу из бота: список пакетов (гемы / пропуски) → способ оплаты
// (Stars — сразу, GRAM (TON) — заявка + модератор пишет сам) → результат. Тег игры привязывается
// только в боте, как у квестов: без тега показываем кнопку «Открыть бота и привязать тег».

function openBotForTag(startParam) {
  const tg = window.Telegram?.WebApp;
  const url = `https://t.me/invitetogamebot?start=${startParam}`;
  if (tg) tg.openTelegramLink(url); else window.open(url, '_blank', 'noopener');
}

function openTelegramUrl(url) {
  const tg = window.Telegram?.WebApp;
  if (tg && /^https:\/\/t\.me\//.test(url)) tg.openTelegramLink(url); else window.open(url, '_blank', 'noopener');
}

function PackageCard({ pkg, egcPass, tagLinked, expanded, onToggle, onDone }) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);
  const [messageOk, setMessageOk] = useState(false);
  const [confirmTon, setConfirmTon] = useState(false);
  const [managerUrl, setManagerUrl] = useState(null);

  async function buy(method) {
    setBusy(true);
    setMessage(null);
    setMessageOk(false);
    try {
      const res = await donatePurchase({ gameKey: pkg.gameKey, packageKey: pkg.key, method });
      if (!res.success) {
        setMessage(res.message);
        return;
      }
      if (method === 'STARS') {
        const status = await openStarsInvoice(res.invoiceUrl);
        if (status === 'paid') {
          setMessage('Оплата прошла! Заявка создана — оплата уже подтверждена, зачислим в ближайшее время. Детали пришлёт бот.');
          setMessageOk(true);
          onDone?.();
        } else if (status === 'failed') {
          setMessage('Платёж не прошёл. Попробуйте ещё раз.');
        }
      } else {
        setConfirmTon(false);
        setMessage(res.message);
        setMessageOk(true);
        setManagerUrl(res.managerUrl);
        onDone?.();
      }
    } catch (e) {
      setMessage(e.message || 'Не удалось оформить заказ.');
    } finally {
      setBusy(false);
    }
  }

  const price = pkg.priceRub.toLocaleString();
  return (
    <div className="shop-card" onClick={() => onToggle(pkg.key)}>
      <div className="shop-top">
        <div className="shop-title">{pkg.label}</div>
        <div className="shop-price">
          {egcPass && pkg.regularPriceRub > pkg.priceRub && (
            <span style={{ textDecoration: 'line-through', opacity: 0.5, marginRight: 6, fontSize: 12 }}>{pkg.regularPriceRub.toLocaleString()}</span>
          )}
          {price} ₽
        </div>
      </div>
      <div className="shop-status">+{pkg.xpBonus.toLocaleString()} XP к покупке{egcPass ? ' · закупочная цена EGC Pass' : ''}</div>
      {expanded && (
        <div className="shop-detail" onClick={e => e.stopPropagation()}>
          {!tagLinked ? (
            <p className="shop-desc">Сначала привяжите тег игры (кнопка выше).</p>
          ) : confirmTon ? (
            <>
              <p className="shop-desc">
                {pkg.label} — ~{pkg.tonAmount} GRAM (TON) ({price} ₽ по текущему курсу).
                После подтверждения модератор свяжется с вами в личных сообщениях, чтобы уточнить детали и прислать реквизиты. Создать заявку?
              </p>
              <button className="quest-btn" disabled={busy} onClick={() => buy('TON')}>
                {busy ? 'Секунду...' : '✅ Создать заявку'}
              </button>
              <button className="quest-btn quest-btn-secondary" style={{ marginTop: 8 }} disabled={busy} onClick={() => setConfirmTon(false)}>
                Назад
              </button>
            </>
          ) : (
            <>
              <p className="shop-desc">
                Выберите способ оплаты. ⭐ Stars списываются сразу автоматически; 💎 GRAM (TON) — перевод вручную, проверка займёт время.
              </p>
              <button className="quest-btn" disabled={busy} onClick={() => buy('STARS')}>
                {busy ? 'Секунду...' : `⭐ Telegram Stars — ${pkg.starsPrice} ⭐`}
              </button>
              <button className="quest-btn quest-btn-secondary" style={{ marginTop: 8 }} disabled={busy} onClick={() => setConfirmTon(true)}>
                💎 GRAM (TON) — ~{pkg.tonAmount}
              </button>
            </>
          )}
          {message && <div className={messageOk ? 'reward-pop' : 'quest-message'} style={{ marginTop: 10 }}>{messageOk && '🎉 '}{message}</div>}
          {managerUrl && (
            <button className="quest-btn quest-btn-secondary" style={{ marginTop: 8 }} onClick={() => openTelegramUrl(managerUrl)}>
              ✍️ Написать менеджеру
            </button>
          )}
        </div>
      )}
    </div>
  );
}

export default function DonateView({ gameKey, onBack }) {
  const [catalog, setCatalog] = useState(null);
  const [error, setError] = useState(null);
  const [type, setType] = useState('currency');
  const [expanded, setExpanded] = useState(null);

  function reload() {
    setError(null);
    getDonateCatalog().then(setCatalog).catch(() => setError('Не удалось загрузить донат. Попробуйте ещё раз.'));
  }

  useEffect(() => { reload(); }, []);

  // Тег привязывается в боте — вернувшись в мини-апп, сразу подтягиваем свежий статус.
  useEffect(() => {
    const onVisible = () => { if (document.visibilityState === 'visible') reload(); };
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('focus', reload);
    return () => {
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('focus', reload);
    };
  }, []);

  if (error) return <div className="page-center error-msg">{error}</div>;
  if (catalog === null) return <div className="page-center">Загрузка...</div>;

  const game = catalog.games.find(g => g.gameKey === gameKey);
  if (!game) return <div className="page-center">Донат для этой игры пока недоступен.</div>;

  const currency = game.packages.filter(p => !p.pass);
  const passes = game.packages.filter(p => p.pass);
  const hasBoth = currency.length > 0 && passes.length > 0;
  const shown = (hasBoth ? (type === 'currency' ? currency : passes) : (currency.length ? currency : passes))
    .map(p => ({ ...p, gameKey }));

  return (
    <div className="category-section">
      <div style={{ marginBottom: 14 }}><BackButton label="Назад" onClick={onBack} /></div>
      <div className="category-header">💰 Донат {game.name}</div>

      {game.tagLinked ? (
        <p className="shop-desc">Зачисление на аккаунт <b>{game.tag}</b>. К каждой покупке — бонус XP.</p>
      ) : (
        <div className="shop-card" style={{ marginBottom: 12 }}>
          <p className="shop-desc">🏷️ Чтобы купить, сначала привяжите тег {game.name} — это делается один раз в боте.</p>
          <button className="quest-btn" onClick={() => openBotForTag(game.tagStartParam)}>🏷️ Открыть бота и привязать тег</button>
        </div>
      )}

      {catalog.egcPass && <p className="shop-desc">⭐ У вас EGC Pass — цены ниже, без наценки клуба.</p>}
      <p className="shop-desc">⚠️ Заявка обрабатывается вручную, зачисление может занять время.</p>
      <p className="shop-desc">⚠️ <b>Важно перед покупкой:</b> {catalog.warning}</p>

      {hasBoth && (
        <div className="view-toggle" style={{ margin: '12px 0' }}>
          <button className={`view-tab ${type === 'currency' ? 'active' : ''}`} onClick={() => { setType('currency'); setExpanded(null); }}>🪙 Гемы</button>
          <button className={`view-tab ${type === 'pass' ? 'active' : ''}`} onClick={() => { setType('pass'); setExpanded(null); }}>🎫 Пропуски</button>
        </div>
      )}

      {shown.map(pkg => (
        <PackageCard
          key={pkg.key}
          pkg={pkg}
          egcPass={catalog.egcPass}
          tagLinked={game.tagLinked}
          expanded={expanded === pkg.key}
          onToggle={k => setExpanded(prev => (prev === k ? null : k))}
        />
      ))}
    </div>
  );
}
