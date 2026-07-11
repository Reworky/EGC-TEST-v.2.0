import { useEffect, useState } from 'react';
import { getQuests, getGames, getQuestDetail, takeQuest, submitQuestReport } from '../api/client';
import './QuestsPage.css';

const CATEGORY_ORDER = ['Лёгкие', 'Средние', 'Сложные'];
const CATEGORY_COLORS = { 'Лёгкие': '#66bb6a', 'Средние': '#ffa726', 'Сложные': '#ef5350' };

function QuestActions({ quest, detail, onChanged }) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);
  const [photo, setPhoto] = useState(null);
  const [externalLink, setExternalLink] = useState('');
  const [comment, setComment] = useState('');

  if (!detail) {
    return <div className="quest-detail-loading">Загрузка...</div>;
  }

  const status = detail.submissionStatus;

  async function handleTake() {
    setBusy(true);
    setMessage(null);
    try {
      const res = await takeQuest(quest.id);
      setMessage(res.message);
      if (res.success) onChanged();
    } finally {
      setBusy(false);
    }
  }

  async function handleSubmit() {
    if (!photo && !externalLink.trim()) {
      setMessage('Прикрепите скриншот или ссылку на подтверждение.');
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const res = await submitQuestReport(quest.id, { photo, externalLink, comment });
      setMessage(res.message);
      if (res.success) {
        setPhoto(null);
        setExternalLink('');
        setComment('');
        onChanged();
      }
    } finally {
      setBusy(false);
    }
  }

  if (status === 'APPROVED') {
    return <div className="quest-status quest-status-approved">✅ Квест выполнен и оплачен</div>;
  }

  if (status === 'PENDING') {
    return <div className="quest-status quest-status-pending">⏳ Отчёт на проверке у модератора</div>;
  }

  if (status === 'DRAFT' || status === 'REJECTED' || status === 'NEEDS_INFO') {
    return (
      <div className="quest-submit-form">
        {(status === 'REJECTED' || status === 'NEEDS_INFO') && detail.moderatorComment && (
          <div className="quest-mod-comment">
            <div className="quest-section-title">Комментарий модератора</div>
            <p>{detail.moderatorComment}</p>
          </div>
        )}
        <input
          type="file"
          accept="image/*"
          onChange={e => setPhoto(e.target.files?.[0] || null)}
          className="quest-file-input"
        />
        <input
          type="text"
          placeholder="Ссылка на подтверждение (необязательно)"
          value={externalLink}
          onChange={e => setExternalLink(e.target.value)}
          className="quest-text-input"
        />
        <textarea
          placeholder="Комментарий (необязательно)"
          value={comment}
          onChange={e => setComment(e.target.value)}
          className="quest-textarea"
        />
        <button className="quest-btn" disabled={busy} onClick={handleSubmit}>
          {busy ? 'Отправка...' : 'Отправить отчёт'}
        </button>
        {message && <div className="quest-message">{message}</div>}
      </div>
    );
  }

  return (
    <div>
      <button className="quest-btn" disabled={busy} onClick={handleTake}>
        {busy ? 'Секунду...' : 'Взять квест'}
      </button>
      {message && <div className="quest-message">{message}</div>}
    </div>
  );
}

export default function QuestsPage() {
  const [games, setGames] = useState([]);
  const [selectedGame, setSelectedGame] = useState(null);
  const [quests, setQuests] = useState([]);
  const [loading, setLoading] = useState(false);
  const [expanded, setExpanded] = useState(null);
  const [details, setDetails] = useState({});

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

  function loadDetail(id) {
    getQuestDetail(id).then(d => setDetails(prev => ({ ...prev, [id]: d })));
  }

  function toggleExpand(id) {
    if (expanded === id) {
      setExpanded(null);
      return;
    }
    setExpanded(id);
    loadDetail(id);
  }

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
            <div key={q.id} className="quest-card" onClick={() => toggleExpand(q.id)}>
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
                <div className="quest-detail" onClick={e => e.stopPropagation()}>
                  <p className="quest-desc">{details[q.id]?.description ?? q.description}</p>
                  {details[q.id]?.instruction && (
                    <>
                      <div className="quest-section-title">Как выполнить</div>
                      <p className="quest-instruction">{details[q.id].instruction}</p>
                    </>
                  )}
                  {details[q.id]?.requirements && (
                    <>
                      <div className="quest-section-title">Требования</div>
                      <p className="quest-requirements">{details[q.id].requirements}</p>
                    </>
                  )}
                  <QuestActions
                    quest={q}
                    detail={details[q.id]}
                    onChanged={() => loadDetail(q.id)}
                  />
                </div>
              )}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}
