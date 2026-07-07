import { useEffect, useState } from 'react';
import { getQuests, getGames } from '../api/client';
import './QuestsPage.css';

const CATEGORY_ORDER = ['Лёгкие', 'Средние', 'Сложные'];
const CATEGORY_COLORS = { 'Лёгкие': '#66bb6a', 'Средние': '#ffa726', 'Сложные': '#ef5350' };

export default function QuestsPage() {
  const [games, setGames] = useState([]);
  const [selectedGame, setSelectedGame] = useState(null);
  const [quests, setQuests] = useState([]);
  const [loading, setLoading] = useState(false);
  const [expanded, setExpanded] = useState(null);

  useEffect(() => {
    getGames().then(g => {
      setGames(g);
      if (g.length > 0) setSelectedGame(g[0]);
    });
  }, []);

  useEffect(() => {
    if (!selectedGame) return;
    setLoading(true);
    setExpanded(null);
    getQuests(selectedGame)
      .then(setQuests)
      .finally(() => setLoading(false));
  }, [selectedGame]);

  const grouped = CATEGORY_ORDER.reduce((acc, cat) => {
    const list = quests.filter(q => q.category === cat);
    if (list.length) acc[cat] = list;
    return acc;
  }, {});

  return (
    <div className="quests-page">
      <div className="game-tabs">
        {games.map(g => (
          <button
            key={g}
            className={`game-tab ${selectedGame === g ? 'active' : ''}`}
            onClick={() => setSelectedGame(g)}
          >
            {g}
          </button>
        ))}
      </div>

      {loading && <div className="page-center">Загрузка...</div>}

      {!loading && Object.entries(grouped).map(([cat, list]) => (
        <div key={cat} className="category-section">
          <div className="category-header" style={{ color: CATEGORY_COLORS[cat] }}>
            {cat}
          </div>
          {list.map(q => (
            <div key={q.id} className="quest-card" onClick={() => setExpanded(expanded === q.id ? null : q.id)}>
              <div className="quest-top">
                <div className="quest-title">{q.title}</div>
                <div className="quest-rewards">
                  <span className="reward-exc">{q.rewardCoins.toLocaleString()} EXC</span>
                  <span className="reward-xp">{q.rewardXp} XP</span>
                </div>
              </div>
              <div className="quest-meta">
                <span className="quest-duration">⏱ {q.durationDays === 1 ? '24 ч' : q.durationDays + ' дн'}</span>
                <span className="quest-platform">{q.platform}</span>
              </div>
              {expanded === q.id && (
                <div className="quest-detail">
                  <p className="quest-desc">{q.description}</p>
                  <div className="quest-section-title">Как выполнить</div>
                  <p className="quest-instruction">{q.instruction}</p>
                  <div className="quest-section-title">Требования</div>
                  <p className="quest-requirements">{q.requirements}</p>
                  <button
                    className="quest-btn"
                    onClick={e => { e.stopPropagation(); window.Telegram?.WebApp?.close(); }}
                  >
                    Взять квест в боте
                  </button>
                </div>
              )}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}
