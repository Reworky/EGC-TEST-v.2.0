import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getPolls, votePoll } from '../api/client';
import './QuestsPage.css';
import './ReferralsPage.css';
import './PollsPage.css';

function PollCard({ poll, onChanged }) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);

  async function handleVote(optionIndex) {
    setBusy(true);
    setMessage(null);
    try {
      const res = await votePoll(poll.id, optionIndex);
      setMessage(res.message);
      if (res.success) onChanged();
    } finally {
      setBusy(false);
    }
  }

  const canVote = !poll.voted && !poll.closed;

  return (
    <div className="ref-link-card">
      <div className="poll-question">🗳 {poll.question}</div>
      {poll.closesAt && !poll.closed && <div className="poll-meta">⏰ Закрытие: {poll.closesAt}</div>}
      {poll.closed && <div className="poll-meta">🔒 Голосование завершено</div>}
      <div className="poll-meta">💰 Стоимость голоса: {poll.priceExc.toLocaleString()} EXC · 👥 Всего голосов: {poll.totalVotes}</div>

      <div className="poll-options">
        {poll.options.map((opt, i) => {
          const cnt = poll.voteCounts[i] || 0;
          const pct = poll.totalVotes > 0 ? Math.round((cnt / poll.totalVotes) * 100) : 0;
          return (
            <div key={i} className="poll-option">
              <div className="poll-option-row">
                <span>{i + 1}. {opt}</span>
                <span>{pct}% ({cnt})</span>
              </div>
              <div className="poll-bar">
                <div className="poll-bar-fill" style={{ width: `${pct}%` }} />
              </div>
              {canVote && (
                <button className="quest-btn poll-vote-btn" disabled={busy} onClick={() => handleVote(i)}>
                  {busy ? 'Секунду...' : 'Голосовать'}
                </button>
              )}
            </div>
          );
        })}
      </div>

      {poll.voted && <div className="quest-status quest-status-approved">✅ Вы уже проголосовали</div>}
      {message && <div className="quest-message">{message}</div>}
    </div>
  );
}

export default function PollsPage() {
  const [polls, setPolls] = useState(null);
  const [error, setError] = useState(null);

  function reload() {
    setError(null);
    getPolls().then(setPolls).catch(() => setError('Не удалось загрузить голосования. Попробуйте ещё раз.'));
  }

  useEffect(() => { reload(); }, []);

  return (
    <div className="ref-page">
      <Link to="/profile" className="ref-back">← Профиль</Link>
      <h2 className="ref-title">🗳 Голосования</h2>

      {error && <div className="page-center error-msg">{error}</div>}
      {!error && polls === null && <div className="page-center">Загрузка...</div>}
      {!error && polls && polls.length === 0 && (
        <div className="page-center">🗳 Активных голосований нет. Следите за обновлениями!</div>
      )}
      {!error && polls && polls.map(poll => (
        <PollCard key={poll.id} poll={poll} onChanged={reload} />
      ))}
    </div>
  );
}
