import { useEffect, useState } from 'react';
import { getLeaderboard } from '../api/client';
import './QuestsPage.css';
import './LeaderboardPage.css';

const MEDALS = ['🥇', '🥈', '🥉'];

function EntryRow({ entry, isMe }) {
  return (
    <div className={`lb-row ${isMe ? 'lb-row-me' : ''}`}>
      <div className="lb-rank">{entry.rank <= 3 ? MEDALS[entry.rank - 1] : entry.rank}</div>
      <div className="lb-info">
        <div className="lb-nickname">{entry.nickname}</div>
        <div className="lb-sub">{entry.levelName}{entry.profileTitle ? ` · ${entry.profileTitle}` : ''}</div>
      </div>
      <div className="lb-xp">{entry.xp.toLocaleString()} XP</div>
    </div>
  );
}

export default function LeaderboardPage() {
  const [type, setType] = useState('overall');
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    setData(null);
    setError(null);
    getLeaderboard(type).then(setData).catch(() => setError('Не удалось загрузить рейтинг. Попробуйте ещё раз.'));
  }, [type]);

  return (
    <div className="lb-page">
      <div className="view-toggle">
        <button className={`view-tab ${type === 'overall' ? 'active' : ''}`} onClick={() => setType('overall')}>
          🌍 Общий
        </button>
        <button className={`view-tab ${type === 'weekly' ? 'active' : ''}`} onClick={() => setType('weekly')}>
          📆 Неделя
        </button>
      </div>

      {type === 'weekly' && (
        <div className="lb-hint">Сбрасывается каждый понедельник</div>
      )}

      {error && <div className="page-center error-msg">{error}</div>}
      {!error && data === null && <div className="page-center">Загрузка...</div>}

      {!error && data && (
        <>
          <div className="lb-list">
            {data.entries.map(entry => (
              <EntryRow key={entry.telegramId} entry={entry} isMe={data.me && entry.telegramId === data.me.telegramId} />
            ))}
            {data.entries.length === 0 && (
              <div className="page-center">Рейтинг пока пуст.</div>
            )}
          </div>

          {data.me && (
            <div className="lb-me-card">
              <div className="lb-me-title">▶ Ты</div>
              <div className="lb-me-row">
                <span>{data.me.rank} место</span>
                <span>{data.me.xp.toLocaleString()} XP</span>
              </div>
              {data.me.leagueName && (
                <div className="lb-me-league">
                  🏅 Лига: <b>{data.me.leagueName}</b>
                  {data.me.leagueExcPrize > 0 && <> · приз +{data.me.leagueExcPrize} EXC</>}
                </div>
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
}
