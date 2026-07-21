import { useEffect, useState } from 'react';
import { getSupportTickets, createSupportTicket } from '../api/client';
import BackButton from '../components/BackButton';
import './QuestsPage.css';
import './ShopPage.css';
import './ReferralsPage.css';

const STATUS_LABELS = {
  OPEN: '🟡 Открыта',
  ANSWERED: '✅ Есть ответ',
  CLOSED: '🔒 Закрыта',
};

function NewTicketForm({ onCreated }) {
  const [text, setText] = useState('');
  const [photo, setPhoto] = useState(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);

  async function handleSubmit() {
    if (!text.trim() && !photo) {
      setMessage('Опишите проблему текстом или приложите скриншот.');
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const res = await createSupportTicket({ text: text.trim(), photo });
      setMessage(res.message);
      if (res.success) {
        setText('');
        setPhoto(null);
        onCreated();
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="ref-link-card">
      <div className="ref-link-label">Новая заявка</div>
      <p className="shop-desc">Опишите проблему — текстом, скриншотом или и тем, и другим. Ответ придёт прямо в бот.</p>
      <textarea
        className="quest-textarea"
        placeholder="Что случилось?"
        value={text}
        onChange={e => setText(e.target.value)}
      />
      <input
        type="file"
        accept="image/*"
        className="quest-file-input"
        onChange={e => setPhoto(e.target.files?.[0] || null)}
        style={{ marginTop: 8 }}
      />
      <button className="quest-btn" disabled={busy} onClick={handleSubmit}>
        {busy ? 'Отправка...' : '✍️ Отправить заявку'}
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
    <div className="category-section">
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

  return (
    <div className="quests-page shop-page">
      <div style={{ padding: '16px 16px 0' }}><BackButton to="/profile" label="Профиль" /></div>

      <div className="view-toggle">
        <button className={`view-tab ${view === 'new' ? 'active' : ''}`} onClick={() => setView('new')}>
          Новая заявка
        </button>
        <button className={`view-tab ${view === 'mine' ? 'active' : ''}`} onClick={() => { setView('mine'); setRefreshKey(k => k + 1); }}>
          Мои заявки
        </button>
      </div>

      {view === 'new'
        ? <div className="category-section"><NewTicketForm onCreated={() => { setView('mine'); setRefreshKey(k => k + 1); }} /></div>
        : <TicketList key={refreshKey} />}
    </div>
  );
}
