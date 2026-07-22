import { useEffect, useRef, useState } from 'react';
import { getQuests, getSponsoredQuests, getGames, getQuestDetail, takeQuest, submitQuestReport, getMyQuests, cancelMyQuest, getTournament, joinTournament, getTournamentLeaderboard } from '../api/client';
import { useLottie } from '../components/LottieContext';
import { useParticles } from '../components/ParticlesContext';
import './QuestsPage.css';

const CATEGORY_ORDER = ['Лёгкие', 'Средние', 'Сложные'];
const CATEGORY_COLORS = { 'Лёгкие': '#66bb6a', 'Средние': '#ffa726', 'Сложные': '#ef5350' };
const CATEGORY_BADGE = { 'Лёгкие': 'easy', 'Средние': 'medium', 'Сложные': 'hard' };
const CATEGORY_CLASS = { 'Лёгкие': 'q-easy', 'Средние': 'q-medium', 'Сложные': 'q-hard' };
const CATEGORY_TICKETS = { 'Лёгкие': 1, 'Средние': 2, 'Сложные': 3 };

function QuestSkeleton() {
  return (
    <div className="quest-skeleton-item">
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 10 }}>
        <div className="skel" style={{ height: 14, width: '55%' }} />
        <div className="skel" style={{ height: 14, width: '20%' }} />
      </div>
      <div className="skel" style={{ height: 11, width: '100%', marginBottom: 6 }} />
      <div className="skel" style={{ height: 11, width: '70%', marginBottom: 12 }} />
      <div style={{ display: 'flex', gap: 8 }}>
        <div className="skel" style={{ height: 20, width: 80 }} />
        <div className="skel" style={{ height: 20, width: 64 }} />
      </div>
    </div>
  );
}

const STATUS_LABELS = {
  DRAFT: 'В процессе',
  PENDING: 'На проверке',
  APPROVED: 'Выполнен',
  REJECTED: 'Отклонён',
  NEEDS_INFO: 'Нужны уточнения',
};
const STATUS_COLORS = {
  DRAFT: '#66bb6a',
  PENDING: '#ffa726',
  APPROVED: '#66bb6a',
  REJECTED: '#ef5350',
  NEEDS_INFO: '#ef5350',
};
const CANCELABLE_STATUSES = ['DRAFT', 'PENDING', 'NEEDS_INFO', 'REJECTED'];

function QuestActions({ quest, detail, onChanged }) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);
  const [photo, setPhoto] = useState(null);
  const [externalLink, setExternalLink] = useState('');
  const [comment, setComment] = useState('');
  const playLottie = useLottie();
  const playParticles = useParticles();
  const prevStatus = useRef(quest.submissionStatus);
  const status = detail?.submissionStatus;

  useEffect(() => {
    if (prevStatus.current !== 'APPROVED' && status === 'APPROVED') {
      playLottie?.();
      playParticles?.('questApproved', 4000);
    }
    prevStatus.current = status;
  }, [status]);

  if (!detail) {
    return <div className="quest-detail-loading">Загрузка...</div>;
  }

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
    return <div className="quest-status quest-status-approved"><i className="ti ti-circle-check"></i> Квест выполнен и оплачен</div>;
  }

  if (status === 'PENDING') {
    return <div className="quest-status quest-status-pending"><i className="ti ti-clock"></i> Отчёт на проверке у модератора</div>;
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
          accept="image/*,video/*"
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

function QuestCard({ q, expanded, onToggle, details, onDetailChanged }) {
  return (
    <div
      className={`quest-card ${CATEGORY_CLASS[q.category] || 'q-ugc'} ${q.submissionStatus ? 'quest-card-taken' : ''}`}
      onClick={() => onToggle(q.id)}
    >
      {/* Шапка: категория + статус */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        {!q.sponsored && q.gameName !== 'UGC' && q.category ? (
          <span className={`quest-cat-badge ${CATEGORY_BADGE[q.category] || 'other'}`}>
            {q.category}
          </span>
        ) : (
          <span className={`quest-cat-badge ${q.sponsored ? 'sponsored' : 'ugc'}`}>
            {q.sponsored ? '💼 Спонсорский' : '🤳 Контент'}
          </span>
        )}
        {q.submissionStatus && (
          <span className="quest-taken-badge" style={{ color: STATUS_COLORS[q.submissionStatus] }}>
            ● {STATUS_LABELS[q.submissionStatus] || q.submissionStatus}
          </span>
        )}
      </div>

      <div className="quest-top">
        <div className="quest-title">{q.title}</div>
        <div className="quest-rewards">
          <span className="reward-exc"><i className="ti ti-coin"></i> {q.rewardCoins.toLocaleString()} EXC</span>
          <span className="reward-xp"><i className="ti ti-star"></i> {q.rewardXp} XP</span>
          {!q.sponsored && q.gameName !== 'UGC' && (q.ticketReward > 0 || CATEGORY_TICKETS[q.category]) && (
            <span className="reward-ticket">🎟 +{q.ticketReward > 0 ? q.ticketReward : CATEGORY_TICKETS[q.category]}</span>
          )}
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
            onChanged={() => onDetailChanged(q.id)}
          />
        </div>
      )}
    </div>
  );
}

function AllQuestsView({ expanded, details, onToggle, onDetailChanged }) {
  const [games, setGames] = useState([]);
  const [selectedGame, setSelectedGame] = useState(null);
  const [quests, setQuests] = useState([]);
  const [loading, setLoading] = useState(false);
  const [ugcQuests, setUgcQuests] = useState([]);
  const [ugcLoading, setUgcLoading] = useState(false);
  const [sponsoredQuests, setSponsoredQuests] = useState([]);
  const [sponsoredLoading, setSponsoredLoading] = useState(false);
  const [openSections, setOpenSections] = useState({ gaming: false, sponsored: false, ugc: false });

  useEffect(() => {
    getGames().then(g => {
      const gameOnly = g.filter(name => name !== 'UGC');
      setGames(gameOnly);
      if (gameOnly.length > 0) setSelectedGame(gameOnly[0]);
    });
    setUgcLoading(true);
    getQuests('UGC').then(setUgcQuests).finally(() => setUgcLoading(false));
    setSponsoredLoading(true);
    getSponsoredQuests().then(setSponsoredQuests).catch(() => setSponsoredQuests([])).finally(() => setSponsoredLoading(false));
  }, []);

  useEffect(() => {
    if (!selectedGame) return;
    setLoading(true);
    getQuests(selectedGame).then(setQuests).finally(() => setLoading(false));
  }, [selectedGame]);

  const grouped = CATEGORY_ORDER.reduce((acc, cat) => {
    const list = quests.filter(q => q.category === cat);
    if (list.length) acc[cat] = list;
    return acc;
  }, {});

  function toggleSection(key) {
    setOpenSections(prev => ({ ...prev, [key]: !prev[key] }));
  }

  return (
    <>
      <div className="quest-section-group">
        <button className="quest-section-header" onClick={() => toggleSection('gaming')}>
          <span>🎮 Игровые квесты</span>
          <span className={`quest-section-chevron ${openSections.gaming ? 'open' : ''}`}>›</span>
        </button>
        {openSections.gaming && (
          <>
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
            {loading && (
              <div className="category-section">
                {[1,2,3].map(i => <QuestSkeleton key={i} />)}
              </div>
            )}
            {!loading && Object.entries(grouped).map(([cat, list]) => (
              <div key={cat} className="category-section">
                <div className="category-header" style={{ color: CATEGORY_COLORS[cat] }}>{cat}</div>
                {list.map(q => (
                  <QuestCard key={q.id} q={q} expanded={expanded} onToggle={onToggle} details={details} onDetailChanged={onDetailChanged} />
                ))}
              </div>
            ))}
          </>
        )}
      </div>

      <div className="quest-section-group">
        <button className="quest-section-header" onClick={() => toggleSection('sponsored')}>
          <span>💼 Спонсорские квесты</span>
          <span className={`quest-section-chevron ${openSections.sponsored ? 'open' : ''}`}>›</span>
        </button>
        {openSections.sponsored && (
          <>
            {sponsoredLoading && <div className="page-center">Загрузка...</div>}
            {!sponsoredLoading && sponsoredQuests.length === 0 && (
              <div className="quest-empty-section">👀 Спонсорские квесты появятся скоро</div>
            )}
            {!sponsoredLoading && sponsoredQuests.length > 0 && (
              <div className="category-section">
                {sponsoredQuests.map(q => (
                  <QuestCard key={q.id} q={q} expanded={expanded} onToggle={onToggle} details={details} onDetailChanged={onDetailChanged} />
                ))}
              </div>
            )}
          </>
        )}
      </div>

      <div className="quest-section-group">
        <button className="quest-section-header" onClick={() => toggleSection('ugc')}>
          <span>📹 Контент-квесты</span>
          <span className={`quest-section-chevron ${openSections.ugc ? 'open' : ''}`}>›</span>
        </button>
        {openSections.ugc && (
          <>
            {ugcLoading && <div className="page-center">Загрузка...</div>}
            {!ugcLoading && ugcQuests.length === 0 && (
              <div className="quest-empty-section">Нет активных контент-квестов</div>
            )}
            {!ugcLoading && ugcQuests.length > 0 && (
              <div className="category-section">
                {ugcQuests.map(q => (
                  <QuestCard key={q.id} q={q} expanded={expanded} onToggle={onToggle} details={details} onDetailChanged={onDetailChanged} />
                ))}
              </div>
            )}
          </>
        )}
      </div>
    </>
  );
}

function MyQuestsView({ expanded, details, onToggle, onDetailChanged }) {
  const [myQuests, setMyQuests] = useState(null);
  const [error, setError] = useState(null);
  const [cancelBusy, setCancelBusy] = useState(null);

  function reload() {
    setError(null);
    getMyQuests().then(setMyQuests).catch(() => setError('Не удалось загрузить квесты. Попробуйте ещё раз.'));
  }

  useEffect(() => { reload(); }, []);

  async function handleCancel(submissionId, e) {
    e.stopPropagation();
    setCancelBusy(submissionId);
    try {
      await cancelMyQuest(submissionId);
      reload();
    } finally {
      setCancelBusy(null);
    }
  }

  if (error) {
    return <div className="page-center error-msg">{error}</div>;
  }

  if (myQuests === null) {
    return <div className="page-center">Загрузка...</div>;
  }

  if (myQuests.length === 0) {
    return <div className="page-center">📭 У вас нет квестов в работе. Откройте «Все квесты» и возьмите первое задание.</div>;
  }

  return (
    <div className="category-section">
      {myQuests.map(m => (
        <div key={m.submissionId} className="quest-card" onClick={() => onToggle(m.questId)}>
          <div className="quest-top">
            <div className="quest-title">{m.title}</div>
            <div className="quest-rewards">
              <span className="reward-exc"><i className="ti ti-coin"></i> {m.rewardCoins.toLocaleString()} EXC</span>
              <span className="reward-xp"><i className="ti ti-star"></i> {m.rewardXp} XP</span>
            </div>
          </div>
          <div className="quest-meta">
            <span style={{ color: STATUS_COLORS[m.status] }}>● {STATUS_LABELS[m.status] || m.status}</span>
            <span className="quest-platform">{m.gameName}</span>
          </div>
          {expanded === m.questId && (
            <div className="quest-detail" onClick={e => e.stopPropagation()}>
              <QuestActions
                quest={{ id: m.questId, title: m.title, rewardXp: m.rewardXp, rewardCoins: m.rewardCoins }}
                detail={details[m.questId]}
                onChanged={() => { onDetailChanged(m.questId); reload(); }}
              />
              {CANCELABLE_STATUSES.includes(m.status) && (
                <button
                  className="quest-btn quest-btn-secondary"
                  disabled={cancelBusy === m.submissionId}
                  onClick={e => handleCancel(m.submissionId, e)}
                >
                  {cancelBusy === m.submissionId ? 'Отмена...' : 'Отменить квест'}
                </button>
              )}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

function TournamentLeaderboard({ id }) {
  const [entries, setEntries] = useState(null);

  useEffect(() => {
    getTournamentLeaderboard(id).then(setEntries).catch(() => setEntries([]));
  }, [id]);

  if (entries === null) return <div className="quest-detail-loading">Загрузка...</div>;
  if (entries.length === 0) return <div className="quest-message">Пока никто не записался.</div>;

  return (
    <div className="category-section" style={{ padding: 0, marginTop: 8 }}>
      {entries.slice(0, 20).map(e => (
        <div key={e.rank} className="quest-meta" style={{ padding: '6px 0' }}>
          <span>{e.rank}. {e.nickname}</span>
          {e.prizeExc > 0 && <span className="reward-exc">🏆 +{e.prizeExc} EXC</span>}
        </div>
      ))}
      {entries.length > 20 && <div className="quest-message">...и ещё {entries.length - 20}</div>}
    </div>
  );
}

function TournamentView() {
  const [tournament, setTournament] = useState(undefined);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);

  function reload() {
    getTournament().then(setTournament).catch(() => setTournament(null));
  }

  useEffect(() => { reload(); }, []);

  async function handleJoin() {
    setBusy(true);
    setMessage(null);
    try {
      const res = await joinTournament(tournament.id);
      setMessage(res.message);
      if (res.success) reload();
    } finally {
      setBusy(false);
    }
  }

  if (tournament === undefined) return <div className="page-center">Загрузка...</div>;
  if (tournament === null) {
    return <div className="page-center">⏳ Активных турниров нет. Следите за новостями клуба!</div>;
  }

  const isReg = tournament.status === 'REGISTRATION';
  const isActive = tournament.status === 'ACTIVE';

  return (
    <div className="category-section">
      <div className="quest-card" style={{ cursor: 'default' }}>
        <div className="quest-top">
          <div className="quest-title">📌 {tournament.name}</div>
        </div>
        <div className="quest-meta" style={{ marginBottom: 8 }}>
          {tournament.gameName && <span>🎮 {tournament.gameName}</span>}
          <span>👥 {tournament.entryCount} участников</span>
        </div>
        <p className="quest-desc" style={{ margin: '0 0 8px' }}>
          💰 Взнос: <b>{tournament.entryFeeExc.toLocaleString()} EXC</b><br />
          🏅 Призовой фонд: <b>{tournament.prizePoolExc.toLocaleString()} EXC</b><br />
          {tournament.startDate && <>🚀 Старт: {tournament.startDate}<br /></>}
          {tournament.endDate && <>⏰ Финиш: {tournament.endDate}<br /></>}
        </p>
        {(isReg || isActive) && (
          <p className="quest-desc" style={{ margin: '0 0 8px' }}>
            {isActive ? '🔥 Турнир идёт! Выполняйте квесты — побеждает тот, кто выполнит больше всего.' : '📋 Идёт регистрация!'}<br />
            🥇 1 место — 60% призового фонда<br />
            🥈-🥉 2–10 места — остаток фонда поровну
          </p>
        )}

        {isReg && !tournament.entered && (
          <button className="quest-btn" disabled={busy} onClick={handleJoin}>
            {busy ? 'Секунду...' : `⚔️ Участвовать (${tournament.entryFeeExc.toLocaleString()} EXC)`}
          </button>
        )}
        {tournament.entered && <div className="quest-status quest-status-approved">✅ Вы зарегистрированы!</div>}
        {message && <div className="quest-message">{message}</div>}

        {(tournament.entered || isActive) && (
          <>
            <div className="quest-section-title" style={{ marginTop: 12 }}>Список участников</div>
            <TournamentLeaderboard id={tournament.id} />
          </>
        )}
      </div>
    </div>
  );
}

export default function QuestsPage() {
  const [view, setView] = useState('all');
  const [expanded, setExpanded] = useState(null);
  const [details, setDetails] = useState({});

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

  function switchView(v) {
    setView(v);
    setExpanded(null);
  }

  return (
    <div className="quests-page">
      <div className="view-toggle">
        <button className={`view-tab ${view === 'all' ? 'active' : ''}`} onClick={() => switchView('all')}>
          Все квесты
        </button>
        <button className={`view-tab ${view === 'mine' ? 'active' : ''}`} onClick={() => switchView('mine')}>
          Мои квесты
        </button>
        <button className={`view-tab ${view === 'tournament' ? 'active' : ''}`} onClick={() => switchView('tournament')}>
          Турнир
        </button>
      </div>

      {view === 'all' && <AllQuestsView expanded={expanded} details={details} onToggle={toggleExpand} onDetailChanged={loadDetail} />}
      {view === 'mine' && <MyQuestsView expanded={expanded} details={details} onToggle={toggleExpand} onDetailChanged={loadDetail} />}
      {view === 'tournament' && <TournamentView />}
    </div>
  );
}
