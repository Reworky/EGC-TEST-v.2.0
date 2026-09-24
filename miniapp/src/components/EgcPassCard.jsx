import { useState } from 'react';
import { getStarsInvoiceLink } from '../api/client';
import { openStarsInvoice } from '../utils/stars';

const EGC_PASS_PERKS = [
  '✨ +10% к EXC за все квесты (до 10 000 EXC бонуса в месяц)',
  '📈 +5% к XP за все квесты',
  '📂 Доп. слот квеста (как «навсегда», пока активна)',
  '🎁 Бесплатный улучшенный сундук каждый день',
  '💰 Донат по играм — гемы и пропуски по закупочной цене, без наценки клуба',
  '⚡ Приоритет в очереди на вывод EXC',
  '💎 Статус-бейдж в профиле',
];

/** Флагманский Stars-товар — подписка (Telegram Star subscription, 30 дней, автопродление на
 * стороне Telegram, 2026-09-15). В отличие от разовых Stars-покупок это не скрывается после оплаты:
 * без подписки карточка предлагает оформить, с подпиской — показывает, что она оформлена, и срок.
 * Живёт в разделе Battle Pass (перенесена из «Предметы» по решению владельца, 2026-09-24). */
export default function EgcPassCard({ profile, price, onPurchased }) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);

  async function handleBuy() {
    setBusy(true);
    setMessage(null);
    try {
      const invoice = await getStarsInvoiceLink('EGC_PASS');
      if (!invoice.success) {
        setMessage(invoice.message);
        return;
      }
      const status = await openStarsInvoice(invoice.url);
      if (status === 'paid') {
        setMessage('✅ EGC Pass активирован!');
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

  const active = profile?.hasEgcPass;

  return (
    <div style={{
      margin: '0 16px 16px', background: 'linear-gradient(135deg, rgba(124,58,237,0.16), rgba(124,58,237,0.05))',
      border: '1px solid rgba(167,139,250,0.4)', borderRadius: 18, padding: '16px 18px',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
        <span style={{ fontSize: 22 }}>⭐</span>
        <div style={{ fontSize: 16, fontWeight: 700, color: '#e9d5ff' }}>EGC Pass</div>
        {active && <span style={{ marginLeft: 'auto', fontSize: 11, fontWeight: 600, color: '#4ade80', background: 'rgba(74,222,128,0.12)', padding: '3px 8px', borderRadius: 8 }}>ОФОРМЛЕНА</span>}
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16, marginBottom: 12 }}>
        {EGC_PASS_PERKS.map(p => (
          <div key={p} style={{ fontSize: 12.5, lineHeight: 1.45, color: 'rgba(255,255,255,0.7)' }}>{p}</div>
        ))}
      </div>
      {message && <div style={{ fontSize: 12, color: 'rgba(255,255,255,0.85)', marginBottom: 10 }}>{message}</div>}
      {active ? (
        <div style={{ fontSize: 12.5, color: 'rgba(255,255,255,0.55)' }}>
          Подписка оформлена, действует до <b style={{ color: '#e9d5ff' }}>{profile.egcPassActiveUntil}</b>,
          дальше продлится автоматически. Отменить можно в настройках платежей Telegram.
        </div>
      ) : (
        <button className="quest-btn" disabled={busy} onClick={handleBuy} style={{ width: '100%' }}>
          {busy ? '...' : `Оформить — ${price} ⭐ / 30 дней`}
        </button>
      )}
    </div>
  );
}
