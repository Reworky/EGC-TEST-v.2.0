import { useEffect, useState } from 'react';
import { getDonateCatalog, donatePurchase } from '../api/client';
import { openStarsInvoice } from '../utils/stars';
import BackButton from '../components/BackButton';
import './QuestsPage.css';
import './ShopPage.css';

// Донат по играм (Купикод) — зеркало флоу из бота: список пакетов (гемы / пропуски) с полной ценой в звёздах и примерно в рублях →
// оплата полной ценой звёздами прямо здесь, скидка за EXC и рубли через менеджера — в боте (23.11.2026, владелец; GRAM (TON) убран 09.11.2026).
// Тег игры привязывается только в боте, как у квестов: без тега показываем кнопку «Открыть бота и привязать тег».

function openBotForTag(startParam) {
  const tg = window.Telegram?.WebApp;
  const url = `https://t.me/invitetogamebot?start=${startParam}`;
  if (tg) tg.openTelegramLink(url); else window.open(url, '_blank', 'noopener');
}

function openTelegramUrl(url) {
  const tg = window.Telegram?.WebApp;
  if (tg && /^https:\/\/t\.me\//.test(url)) tg.openTelegramLink(url); else window.open(url, '_blank', 'noopener');
}

function PackageCard({ pkg, egcPass, coins, tagLinked, expanded, onToggle, onDone }) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);
  const [messageOk, setMessageOk] = useState(false);
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
          {pkg.starsPrice.toLocaleString('ru-RU')} ⭐{' '}
          <span style={{ fontSize: 12, opacity: 0.7 }}>
            (≈ {egcPass && pkg.regularPriceRub > pkg.priceRub && (
              <span style={{ textDecoration: 'line-through', opacity: 0.6, marginRight: 4 }}>{pkg.regularPriceRub.toLocaleString()}</span>
            )}{price} ₽)
          </span>
        </div>
      </div>
      <div className="shop-status">+{pkg.xpBonus.toLocaleString()} XP к покупке{egcPass ? ' · закупочная цена EGC Pass' : ''}</div>
      {pkg.excStars > 0 && (coins >= pkg.excPrice ? (
        <div className="shop-status">🪙 Со скидкой за EXC: {pkg.excStars.toLocaleString('ru-RU')} ⭐ + {pkg.excPrice.toLocaleString('ru-RU')} EXC</div>
      ) : (
        <div className="shop-status">🪙 Скидка за EXC: {pkg.excStars.toLocaleString('ru-RU')} ⭐ + {pkg.excPrice.toLocaleString('ru-RU')} EXC (не хватает {(pkg.excPrice - coins).toLocaleString('ru-RU')} EXC)</div>
      ))}
      {!egcPass && pkg.passPriceRub > 0 && pkg.regularPriceRub - pkg.passPriceRub >= 20 && (
        <div className="shop-status">⭐ С EGC Pass: {pkg.passPriceRub.toLocaleString()} ₽, экономия {(pkg.regularPriceRub - pkg.passPriceRub).toLocaleString()} ₽</div>
      )}
      {expanded && (
        <div className="shop-detail" onClick={e => e.stopPropagation()}>
          {!tagLinked ? (
            <p className="shop-desc">Сначала укажите логин или привяжите тег игры (кнопка выше).</p>
          ) : (
            <>
              <p className="shop-desc">
                Оплатите звёздами Telegram прямо здесь. {pkg.excStars > 0 && coins >= pkg.excPrice ? 'Скидку за EXC и оплату рублями через менеджера оформите в боте (раздел «Магазин» → донат).' : 'Оплата рублями через менеджера — в боте (раздел «Магазин» → донат).'}
              </p>
              <button className="quest-btn" disabled={busy} onClick={() => buy('STARS')}>
                ⭐ Оплатить {pkg.starsPrice.toLocaleString('ru-RU')} ⭐
              </button>
              <button className="quest-btn quest-btn-secondary" style={{ marginTop: 8 }} onClick={() => openTelegramUrl('https://t.me/invitetogamebot')}>
                🤖 {pkg.excStars > 0 && coins >= pkg.excPrice ? 'Со скидкой за EXC — в боте' : 'Открыть бота'}
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

      {game.gameKey === 'steam_topup' && (
        <p className="shop-desc">Пополнение работает только для аккаунтов Steam из стран: Россия, Беларусь, Казахстан, Узбекистан, Армения, Киргизия, Молдова, Таджикистан, Туркменистан. Нужен только логин Steam (не ник и не email), пароль не нужен.</p>
      )}
      {game.tagLinked ? (
        <p className="shop-desc">{game.gameKey === 'steam_topup' ? 'Зачисление на логин Steam' : 'Зачисление на аккаунт'} <b>{game.tag}</b>. К каждой покупке — бонус XP.</p>
      ) : (
        <div className="shop-card" style={{ marginBottom: 12 }}>
          <p className="shop-desc">{game.gameKey === 'steam_topup' ? '🎮 Чтобы купить, сначала укажите логин Steam — это делается один раз в боте.' : `🏷️ Чтобы купить, сначала привяжите тег ${game.name} — это делается один раз в боте.`}</p>
          <button className="quest-btn" onClick={() => openBotForTag(game.tagStartParam)}>{game.gameKey === 'steam_topup' ? '🎮 Открыть бота и указать логин' : '🏷️ Открыть бота и привязать тег'}</button>
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
          coins={catalog.coins || 0}
          tagLinked={game.tagLinked}
          expanded={expanded === pkg.key}
          onToggle={k => setExpanded(prev => (prev === k ? null : k))}
        />
      ))}
    </div>
  );
}
