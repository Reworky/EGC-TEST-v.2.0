import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { getDailyTasks, claimDailyTasks } from '../api/client';

/** «Задания дня»: бонус, квест, сундук дня → «Сундук заданий» (те же правила и сервис, что в боте). */
export default function DailyTasksCard({ refreshKey, onClaimed }) {
  const navigate = useNavigate();
  const [data, setData] = useState(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);
  const [messageOk, setMessageOk] = useState(false);

  function load() {
    return getDailyTasks().then(setData).catch(() => {});
  }

  // refreshKey меняется, когда игрок забрал бонус/сундук прямо на странице — отметки обновляются сразу.
  useEffect(() => { load(); }, [refreshKey]);

  // Пока грузится, функция выключена или сервер недоступен — карточки просто нет.
  if (!data || !data.enabled) return null;

  async function handleClaim() {
    setBusy(true);
    setMessage(null);
    try {
      const res = await claimDailyTasks();
      setMessage(res.message);
      setMessageOk(!!res.success);
      await load();
      if (res.success) onClaimed?.();
    } catch {
      setMessage('Не получилось, попробуйте чуть позже.');
      setMessageOk(false);
    } finally {
      setBusy(false);
    }
  }

  const questText = data.firstQuest ? 'Выполни первый квест' : 'Выполни 1 квест';
  const rows = [
    { done: data.bonusDone, text: 'Забери ежедневный бонус' },
    { done: data.questDone, text: questText, action: !data.questDone && !data.claimed ? () => navigate('/quests') : null },
    { done: data.chestDone, text: 'Открой сундук дня' },
  ];

  return (
    <div className="ref-progress-card dt-card">
      <div className="dt-head">
        <span className="dt-title">📋 Задания дня</span>
        <span className="dt-count">{data.doneCount}/3</span>
      </div>
      <div className="dt-list">
        {rows.map(r => (
          <div key={r.text} className={`dt-row ${r.done ? 'done' : ''}`}>
            <span className="dt-mark">{r.done ? '✅' : '⬜'}</span>
            <span className="dt-text">{r.text}</span>
            {r.action && <button type="button" className="dt-go" onClick={r.action}>К квестам →</button>}
          </div>
        ))}
      </div>
      <div className="dt-reward">
        🎁 За все три — Сундук заданий: +{data.rewardExc} EXC и 🎟️ {data.rewardTickets} билет колеса
      </div>
      {data.ready && (
        <button className="quest-btn" disabled={busy} onClick={handleClaim}>
          {busy ? 'Секунду...' : '🎁 Забрать сундук заданий'}
        </button>
      )}
      {data.claimed && !message && <div className="dt-claimed">✅ Сундук заданий сегодня получен. Новые задания — завтра.</div>}
      {message && <div className={`dt-message ${messageOk ? 'ok' : ''}`}>{message}</div>}
    </div>
  );
}
