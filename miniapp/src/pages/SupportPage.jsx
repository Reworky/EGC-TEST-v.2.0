import { useEffect, useState } from 'react';
import { getSupportTickets, getSupportManagerLink, getSupportFaqGate, markSupportFaqSeen, getSupportHours } from '../api/client';
import BackButton from '../components/BackButton';
import './QuestsPage.css';
import './ShopPage.css';
import './ReferralsPage.css';

const STATUS_LABELS = {
  OPEN: '🟡 Открыта',
  ANSWERED: '✅ Есть ответ',
  CLOSED: '🔒 Закрыта',
};

const BOT_FAQ_LINK = 'https://t.me/invitetogamebot?start=faq';

/** Первое обращение: сначала предлагаем заглянуть в FAQ (в боте), и только потом показываем форму. */
function FaqGateCard({ onContinue }) {
  async function openFaq() {
    try { await markSupportFaqSeen(); } catch { /* не мешаем открыть FAQ */ }
    const tg = window.Telegram?.WebApp;
    if (tg?.openTelegramLink) tg.openTelegramLink(BOT_FAQ_LINK);
    else window.open(BOT_FAQ_LINK, '_blank');
  }

  async function proceed() {
    try { await markSupportFaqSeen(); } catch { /* форму всё равно открываем */ }
    onContinue();
  }

  return (
    <div className="ref-link-card">
      <div className="ref-link-label">💡 Сначала загляните в FAQ</div>
      <p className="shop-desc">
        В FAQ собраны ответы на частые вопросы: про квесты, вывод EXC, награды и отряды. Возможно, нужный ответ уже там.
        Не нашли — напишите в поддержку, мы ответим.
      </p>
      <button className="quest-btn" onClick={openFaq}>❓ Открыть FAQ</button>
      <button className="quest-btn quest-btn-secondary" style={{ marginTop: 8 }} onClick={proceed}>
        ✍️ Не нашёл ответ — написать
      </button>
    </div>
  );
}

/** С 18.10.2026 обращение в поддержку заканчивается личным чатом с менеджером в Telegram (текст с ником и ID подставлен), а не формой заявки. */
function NewTicketForm() {
  const [hours, setHours] = useState(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);

  useEffect(() => { getSupportHours().then(setHours).catch(() => {}); }, []);

  async function openManagerChat() {
    setBusy(true);
    setMessage(null);
    try {
      const { url } = await getSupportManagerLink();
      const tg = window.Telegram?.WebApp;
      if (tg?.openTelegramLink) tg.openTelegramLink(url);
      else window.open(url, '_blank');
    } catch {
      setMessage('Не удалось открыть чат. Попробуйте ещё раз.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="ref-link-card">
      <div className="ref-link-label">Написать менеджеру</div>
      <p className="shop-desc">
        Нажми кнопку, и откроется личный чат с менеджером. Текст с твоим ником и ID уже подставлен: опиши вопрос, при необходимости приложи скриншот и нажми «Отправить».
      </p>
      {hours && (
        <p className="shop-desc">
          🕘 Часы работы: {hours.text}.
          {!hours.open && <><br /><b>{hours.notice}</b></>}
        </p>
      )}
      <button className="quest-btn" disabled={busy} onClick={openManagerChat}>
        {busy ? 'Открываю...' : '💬 Написать менеджеру'}
      </button>
      {message && <div className="quest-message">{message}</div>}
    </div>
  );
}

function TicketList() {
  const [tickets, setTickets] = useState(null);
  const [error, setError] = useState(null);

  function reload() {
    setError(null);
    getSupportTickets().then(setTickets).catch(() => setError('Не удалось загрузить заявки. Попробуйте ещё раз.'));
  }

  useEffect(() => { reload(); }, []);

  if (error) return <div className="page-center error-msg">{error}</div>;
  if (tickets === null) return <div className="page-center">Загрузка...</div>;
  if (tickets.length === 0) {
    return <div className="page-center">📭 У вас пока нет заявок в поддержку.</div>;
  }

  return (
    <div className="category-section" style={{ padding: '12px 16px', display: 'flex', flexDirection: 'column', gap: 12 }}>
      {tickets.map(t => (
        <div key={t.id} className="shop-card">
          <div className="shop-top">
            <div className="shop-title">🆘 Заявка #{t.id}</div>
            <div className="shop-price">{STATUS_LABELS[t.status] || t.status}</div>
          </div>
          <div className="shop-meta">
            <span className="shop-date">Обновлено: {t.updatedAt}</span>
          </div>
          <p className="shop-desc">💬 {t.initialMessage}</p>
          {t.lastModeratorReply && (
            <div className="quest-mod-comment">
              <div className="quest-section-title">Ответ поддержки</div>
              <p>{t.lastModeratorReply}</p>
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

export default function SupportPage() {
  const [view, setView] = useState('new');
  const [refreshKey, setRefreshKey] = useState(0);
  const [notice, setNotice] = useState(null);
  // null — ещё не знаем, true — показать FAQ перед формой, false — сразу форма
  const [faqGate, setFaqGate] = useState(null);

  useEffect(() => {
    getSupportFaqGate().then(r => setFaqGate(!!r.needed)).catch(() => setFaqGate(false));
  }, []);

  return (
    <div className="quests-page shop-page">
      <div style={{ padding: '16px 16px 8px' }}><BackButton to="/profile" label="Профиль" /></div>

      <div className="view-toggle">
        <button className={`view-tab ${view === 'new' ? 'active' : ''}`} onClick={() => setView('new')}>
          Написать
        </button>
        <button className={`view-tab ${view === 'mine' ? 'active' : ''}`} onClick={() => { setView('mine'); setRefreshKey(k => k + 1); }}>
          История
        </button>
      </div>

      {view === 'new'
        ? (
          <div className="category-section" style={{ padding: '12px 16px' }}>
            {faqGate === null
              ? <div className="page-center">Загрузка...</div>
              : faqGate
                ? <FaqGateCard onContinue={() => setFaqGate(false)} />
                : <NewTicketForm />}
          </div>
        )
        : (
          <>
            {notice && <div className="quest-message" style={{ margin: '12px 16px 0' }}>{notice}</div>}
            <TicketList key={refreshKey} />
          </>
        )}
    </div>
  );
}
