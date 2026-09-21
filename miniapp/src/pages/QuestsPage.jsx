import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { getQuests, getSponsoredQuests, getGames, getGamePhotoUrl, getQuestBoost, getQuestDetail, takeQuest, submitQuestReport, getMyQuests, cancelMyQuest, getTournament, joinTournament, getTournamentLeaderboard, getTournamentPhotoUrl, getRecommendedQuest } from '../api/client';
import { useLottie } from '../components/LottieContext';
import { useParticles } from '../components/ParticlesContext';
import AdRewardCard from '../components/AdRewardCard';
import './QuestsPage.css';

const CATEGORY_ORDER = ['Лёгкие', 'Средние', 'Сложные'];
const CATEGORY_COLORS = { 'Лёгкие': '#66bb6a', 'Средние': '#ffa726', 'Сложные': '#ef5350' };
const CATEGORY_BADGE = { 'Лёгкие': 'easy', 'Средние': 'medium', 'Сложные': 'hard' };
const CATEGORY_CLASS = { 'Лёгкие': 'q-easy', 'Средние': 'q-medium', 'Сложные': 'q-hard' };
const CATEGORY_TICKETS = { 'Лёгкие': 1, 'Средние': 2, 'Сложные': 3 };
const QUEST_SECTIONS = [
  { key: 'gaming', label: '🎮 Игровые' },
  { key: 'sponsored', label: '💼 Спонсоры' },
  { key: 'ugc', label: '📹 UGC' },
  { key: 'ads', label: '🎬 Реклама' },
];

function LinkPill({ url }) {
  const [copied, setCopied] = useState(false);

  function handleOpen(e) {
    e.stopPropagation();
    const tg = window.Telegram?.WebApp;
    if (tg) {
      url.includes('t.me/') ? tg.openTelegramLink(url) : tg.openLink(url);
    } else {
      window.open(url, '_blank', 'noopener');
    }
  }

  function handleCopy(e) {
    e.stopPropagation();
    navigator.clipboard.writeText(url).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  }

  const display = url.replace(/^https?:\/\//, '').replace(/\/$/, '');

  return (
    <span className="quest-link-pill" onClick={e => e.stopPropagation()}>
      <span className="quest-link-url" onClick={handleOpen}>🔗 {display}</span>
      <button className="quest-link-copy" onClick={handleCopy} title="Копировать ссылку">
        {copied ? '✓' : '⎘'}
      </button>
    </span>
  );
}

function renderTextWithLinks(text) {
  if (!text) return null;
  const parts = text.split(/(https?:\/\/[^\s<>"']+)/);
  return parts.map((part, i) =>
    /^https?:\/\//.test(part) ? <LinkPill key={i} url={part} /> : part
  );
}

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
  const [needsTagLink, setNeedsTagLink] = useState(null);
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

  const TAG_LINK_DEEPLINK = {
    NEEDS_BRAWL_TAG: { startParam: 'brawltag', label: '🏷️ Открыть бота и привязать тег' },
    NEEDS_CLASH_TAG: { startParam: 'clashtag', label: '🏷️ Открыть бота и привязать тег' },
    NEEDS_CLASH_ROYALE_TAG: { startParam: 'crtag', label: '🏷️ Открыть бота и привязать тег' },
    NEEDS_DOTA_LINK: { startParam: 'dotalink', label: '🏷️ Открыть бота и привязать тег' },
    NEEDS_CS2_LINK: { startParam: 'cs2link', label: '🏷️ Открыть бота и привязать тег' },
    NEEDS_PUBG_LINK: { startParam: 'pubglink', label: '🏷️ Открыть бота и привязать тег' },
    // Подписка на канал теперь проверяется при взятии квеста, а не сразу после анкеты (2026-09-14) —
    // тот же принцип, что и с привязкой тега: показать сообщение и увести в бота для завершения шага.
    NEEDS_CHANNEL_SUBSCRIPTION: { startParam: 'subscribe', label: '📢 Открыть бота и подписаться' },
  };

  function openBotForTag(startParam) {
    const tg = window.Telegram?.WebApp;
    const url = `https://t.me/invitetogamebot?start=${startParam}`;
    if (tg) tg.openTelegramLink(url); else window.open(url, '_blank', 'noopener');
  }

  async function handleTake() {
    setBusy(true);
    setMessage(null);
    setNeedsTagLink(null);
    try {
      const res = await takeQuest(quest.id);
      setMessage(res.message);
      setNeedsTagLink(TAG_LINK_DEEPLINK[res.status] || null);
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
    } catch (e) {
      setMessage(e?.response?.data?.message || 'Ошибка отправки. Попробуйте ещё раз.');
    } finally {
      setBusy(false);
    }
  }

  // Квест-веха (oneTimePerAccount) и внешние партнёрские авто-квесты — единственные случаи, где
  // повтор в принципе невозможен, поэтому только для них тупиковое сообщение навсегда. Для всех
  // остальных APPROVED-квестов (обычных с суточным кулдауном и decay-пилота без кулдауна вовсе)
  // показываем кнопку "Пройти ещё раз" — если кулдаун ещё не истёк, об этом скажет ответ сервера
  // при нажатии ("Этот квест можно выполнять не чаще 1 раза в ..."), а не молчаливый дед-энд.
  if (status === 'APPROVED' && (detail.externalAutoApprove || detail.oneTimePerAccount)) {
    return <div className="quest-status quest-status-approved"><i className="ti ti-circle-check"></i> Квест выполнен и оплачен</div>;
  }

  if (status === 'APPROVED') {
    const note = detail.repeatableNoCooldownEligible
      ? 'Засчитано! Награда за следующее прохождение сегодня — меньше, завтра снова полная.'
      : 'Выполнен и оплачен. Когда закончится кулдаун на повтор — можно пройти ещё раз.';
    return (
      <div>
        <div className="quest-status quest-status-approved" style={{ marginBottom: 8 }}>
          <i className="ti ti-circle-check"></i> {note}
        </div>
        <button className="quest-btn" disabled={busy} onClick={handleTake}>
          {busy ? 'Секунду...' : 'Пройти ещё раз'}
        </button>
        {message && <div className="quest-message">{message}</div>}
      </div>
    );
  }

  if (status === 'PENDING') {
    return <div className="quest-status quest-status-pending"><i className="ti ti-clock"></i> Отчёт на проверке у модератора</div>;
  }

  if (detail.externalAutoApprove && status === 'DRAFT') {
    return <div className="quest-status quest-status-pending"><i className="ti ti-clock"></i> Ждём подтверждения от партнёра — отчёт отправлять не нужно</div>;
  }

  if (detail.brawlAutoVerify && status === 'DRAFT') {
    const target = detail.autoVerifyTarget;
    const progress = detail.autoVerifyProgress;
    if (target != null && progress != null) {
      const pct = Math.min(100, Math.round((progress / target) * 100));
      return (
        <div className="quest-status quest-status-pending">
          <div><i className="ti ti-clock"></i> Прогресс отслеживается автоматически по вашему аккаунту {detail.gameName}</div>
          <div className="quest-progress-track"><div className="quest-progress-fill" style={{ width: pct + '%' }} /></div>
          <div className="quest-progress-label">{progress} / {target}</div>
        </div>
      );
    }
    return (
      <div className="quest-status quest-status-pending">
        <i className="ti ti-clock"></i> Прогресс отслеживается автоматически по вашему аккаунту {detail.gameName} — идёт первый замер…
      </div>
    );
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
      {message && (
        <div className="quest-message">
          {message}
          {needsTagLink && (
            <button className="quest-btn" style={{ marginTop: 8 }} onClick={() => openBotForTag(needsTagLink.startParam)}>
              {needsTagLink.label}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function QuestCard({ q, expanded, onToggle, details, onDetailChanged }) {
  // Статус берём из уже подгруженного детейла квеста, если он есть (details обновляется сразу после
  // взятия/сдачи отчёта), иначе — из общего списка квестов игры (details ещё не запрашивался). Без этого
  // бейдж в свёрнутой карточке "Все квесты" оставался бы старым до перезагрузки всего списка игры.
  const detail = details[q.id];
  const submissionStatus = detail?.submissionStatus ?? q.submissionStatus;
  const brawlAutoVerify = detail?.brawlAutoVerify ?? q.brawlAutoVerify;
  const externalAutoApprove = detail?.externalAutoApprove ?? q.externalAutoApprove;

  // Бейдж "🆕 Новое" на каждой карточке был избыточен, когда сразу много квестов одной игры
  // становятся "новыми" одновременно (например, все квесты игры добавлены одним деплоем) —
  // вместо флажка подсвечиваем фон карточки, и только пока квест ещё не взят (после взятия
  // важнее статус прохождения, чем то, что квест недавно добавили).
  const isNew = q.highlightNew && !submissionStatus;

  return (
    <div
      className={`quest-card ${q.gameName === 'UGC' ? 'q-ugc' : (CATEGORY_CLASS[q.category] || 'q-flat')} ${submissionStatus ? 'quest-card-taken' : ''} ${isNew ? 'quest-card-new' : ''}`}
      onClick={() => onToggle(q.id)}
    >
      {/* Шапка: категория + статус */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
          {q.sponsored ? (
            <span className="quest-cat-badge sponsored">💼 Спонсорский</span>
          ) : q.gameName === 'UGC' ? (
            <span className="quest-cat-badge ugc">🤳 Контент</span>
          ) : q.category ? (
            <span className={`quest-cat-badge ${CATEGORY_BADGE[q.category] || 'other'}`}>
              {q.category}
            </span>
          ) : null}
        </div>
        {submissionStatus && (
          <span className="quest-taken-badge" style={{ color: STATUS_COLORS[submissionStatus] }}>
            ● {submissionStatus === 'DRAFT' && (brawlAutoVerify || externalAutoApprove)
                ? 'Авто-отслеживание'
                : (STATUS_LABELS[submissionStatus] || submissionStatus)}
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
          <p className="quest-desc">{renderTextWithLinks(details[q.id]?.description ?? q.description)}</p>
          {details[q.id]?.instruction && (
            <>
              <div className="quest-section-title">Как выполнить</div>
              <p className="quest-instruction">{renderTextWithLinks(details[q.id].instruction)}</p>
            </>
          )}
          {details[q.id]?.requirements && (
            <>
              <div className="quest-section-title">
                {(details[q.id]?.brawlAutoVerify || details[q.id]?.externalAutoApprove) ? 'ℹ️ Информация' : 'Требования'}
              </div>
              <p className="quest-requirements">{renderTextWithLinks(details[q.id].requirements)}</p>
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

function QuestBoostBanner() {
  const [boost, setBoost] = useState(null);
  const [countdown, setCountdown] = useState(null);

  useEffect(() => {
    getQuestBoost().then(setBoost).catch(() => setBoost(null));
  }, []);

  useEffect(() => {
    if (!boost?.active || !boost?.endsAt) { setCountdown(null); return; }
    const tick = () => {
      const diff = new Date(boost.endsAt).getTime() - Date.now();
      if (diff <= 0) { setCountdown(null); return; }
      const totalSeconds = Math.floor(diff / 1000);
      const h = String(Math.floor(totalSeconds / 3600)).padStart(2, '0');
      const m = String(Math.floor((totalSeconds % 3600) / 60)).padStart(2, '0');
      const s = String(totalSeconds % 60).padStart(2, '0');
      setCountdown(`${h}:${m}:${s}`);
    };
    tick();
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [boost?.active, boost?.endsAt]);

  if (!boost?.active || !countdown) return null;

  return (
    <div className="quest-boost-banner">
      🔥 Буст выходных ×{boost.multiplier}! EXC за квесты умножены
      <div className="quest-boost-banner-timer">Осталось: {countdown}</div>
    </div>
  );
}

function GameCard({ name, active, onClick }) {
  const [imgFailed, setImgFailed] = useState(false);

  return (
    <button className={`game-card ${active ? 'active' : ''}`} onClick={onClick}>
      {!imgFailed && (
        <img
          className="game-card-img"
          src={getGamePhotoUrl(name)}
          alt={name}
          loading="lazy"
          onError={() => setImgFailed(true)}
        />
      )}
      <div className="game-card-scrim" />
      <span className="game-card-name">{name}</span>
    </button>
  );
}

function AllQuestsView({ expanded, details, onToggle, onDetailChanged, initialSection }) {
  const [games, setGames] = useState([]);
  const [selectedGame, setSelectedGame] = useState(null);
  const [quests, setQuests] = useState([]);
  const [loading, setLoading] = useState(false);
  const [ugcQuests, setUgcQuests] = useState([]);
  const [ugcLoading, setUgcLoading] = useState(false);
  const [sponsoredQuests, setSponsoredQuests] = useState([]);
  const [sponsoredLoading, setSponsoredLoading] = useState(false);
  const [activeSection, setActiveSection] = useState(initialSection === 'ads' ? 'ads' : 'gaming');
  const [recommended, setRecommended] = useState(null);
  const questListRef = useRef(null);

  useEffect(() => {
    getRecommendedQuest().then(setRecommended).catch(() => setRecommended(null));
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

  // Клик по карточке игры сразу прокручивает к списку квестов этой игры ниже.
  function handleSelectGame(g) {
    setSelectedGame(g);
    requestAnimationFrame(() => {
      questListRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
  }

  const flatQuests = quests.filter(q => !q.category);
  const grouped = CATEGORY_ORDER.reduce((acc, cat) => {
    const list = quests.filter(q => q.category === cat);
    if (list.length) acc[cat] = list;
    return acc;
  }, {});

  // games уже отсортирован бэкендом по интересу пользователя (sortGamesByInterest) — верхние N
  // считаем "лучшими" без доп. логики на фронте.
  const topGames = games.slice(0, 3);
  const restGames = games.slice(3);

  return (
    <>
      <QuestBoostBanner />

      <div className="view-toggle quest-section-tabs">
        {QUEST_SECTIONS.map(s => (
          <button
            key={s.key}
            className={`view-tab ${activeSection === s.key ? 'active' : ''}`}
            onClick={() => setActiveSection(s.key)}
          >
            {s.label}
          </button>
        ))}
      </div>

      {activeSection === 'gaming' && (
        <>
          {recommended && (
            <div className="game-section">
              <div className="game-section-title">🎯 Твой квест сейчас</div>
              <div className="category-section">
                <QuestCard q={recommended} expanded={expanded} onToggle={onToggle} details={details} onDetailChanged={onDetailChanged} />
              </div>
            </div>
          )}
          {topGames.length > 0 && (
            <div className="game-section">
              <div className="game-section-title">🔥 Лучшие игры</div>
              <div className="game-grid">
                {topGames.map(g => (
                  <GameCard key={g} name={g} active={selectedGame === g} onClick={() => handleSelectGame(g)} />
                ))}
              </div>
            </div>
          )}
          {restGames.length > 0 && (
            <div className="game-section">
              <div className="game-section-title">Все игры</div>
              <div className="game-grid">
                {restGames.map(g => (
                  <GameCard key={g} name={g} active={selectedGame === g} onClick={() => handleSelectGame(g)} />
                ))}
              </div>
            </div>
          )}

          <div ref={questListRef} className="game-section">
            <div className="game-section-title">
              🎯 Доступные квесты{selectedGame ? ` — ${selectedGame}` : ''}
            </div>
            {loading && (
              <div className="category-section">
                {[1,2,3].map(i => <QuestSkeleton key={i} />)}
              </div>
            )}
            {!loading && flatQuests.length > 0 && (
              <div className="category-section">
                {flatQuests.map(q => (
                  <QuestCard key={q.id} q={q} expanded={expanded} onToggle={onToggle} details={details} onDetailChanged={onDetailChanged} />
                ))}
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
          </div>
        </>
      )}

      {activeSection === 'sponsored' && (
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

      {activeSection === 'ugc' && (
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

      {activeSection === 'ads' && (
        <div className="category-section">
          <AdRewardCard />
        </div>
      )}
    </>
  );
}

function MyQuestsView({ expanded, details, onToggle, onDetailChanged }) {
  const [myQuests, setMyQuests] = useState(null);
  const [error, setError] = useState(null);
  const [cancelBusy, setCancelBusy] = useState(null);
  // onDetailChanged (=loadDetail в родителе) не обёрнут в useCallback — новая ссылка на каждый
  // рендер. Держим текущие expanded/onDetailChanged в ref, чтобы не пересоздавать интервал ниже
  // на каждый чих родителя (эффект с пустыми зависимостями настраивается один раз при монтировании).
  const expandedRef = useRef(expanded);
  const onDetailChangedRef = useRef(onDetailChanged);
  expandedRef.current = expanded;
  onDetailChangedRef.current = onDetailChanged;

  function reload() {
    setError(null);
    getMyQuests().then(setMyQuests).catch(() => setError('Не удалось загрузить квесты. Попробуйте ещё раз.'));
  }

  useEffect(() => {
    // Авто-верификация (Brawl Stars/Clash/Dota/CS2/PUBG) засчитывает квест в фоне на сервере —
    // без опроса игрок видел бы старый статус ("Авто-отслеживание", 0/N), пока не выйдет и не
    // зайдёт заново на этот экран. Обновление в чате бота приходит всегда, но если игрок сидит
    // именно тут в ожидании — экран должен обновляться сам. reload() обновляет сам список (бейджи
    // статуса свёрнутых карточек), а onDetailChanged(expanded) — прогресс-бар РАЗВЁРНУТОЙ карточки
    // (он читается из отдельного details[id], а не из списка) — без второго вызова прогресс-бар
    // у открытой карточки не двигался бы, пока список рядом уже обновился.
    function tick() {
      reload();
      if (expandedRef.current != null) onDetailChangedRef.current(expandedRef.current);
    }
    tick();
    const interval = setInterval(tick, 15000);
    function onVisible() { if (document.visibilityState === 'visible') tick(); }
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      clearInterval(interval);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, []);

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
    <div className="category-section" style={{ marginTop: 12 }}>
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
            <span style={{ color: STATUS_COLORS[m.status] }}>
              ● {m.status === 'DRAFT' && (m.brawlAutoVerify || m.externalAutoApprove)
                  ? 'Авто-отслеживание'
                  : (STATUS_LABELS[m.status] || m.status)}
            </span>
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
  const [bannerFailed, setBannerFailed] = useState(false);

  function reload() {
    getTournament().then(setTournament).catch(() => setTournament(null));
  }

  useEffect(() => { reload(); }, []);
  useEffect(() => { setBannerFailed(false); }, [tournament?.id]);

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
    <div className="category-section" style={{ marginTop: 12 }}>
      <div className="quest-card" style={{ cursor: 'default' }}>
        {!bannerFailed && (
          <img
            className="tournament-banner"
            src={getTournamentPhotoUrl(tournament.id)}
            alt={tournament.name}
            loading="lazy"
            onError={() => setBannerFailed(true)}
          />
        )}
        <div className="quest-top">
          <div className="quest-title">📌 {tournament.name}</div>
        </div>
        <div className="quest-meta" style={{ marginBottom: 8 }}>
          {tournament.gameName && <span>🎮 {tournament.gameName}</span>}
          <span>👥 {tournament.entryCount} участников</span>
        </div>
        {tournament.description && (
          <p className="quest-desc" style={{ margin: '0 0 8px' }}>{tournament.description}</p>
        )}
        <p className="quest-desc" style={{ margin: '0 0 8px' }}>
          💰 Взнос: <b>{tournament.entryFeeExc.toLocaleString()} EXC</b><br />
          🏅 Призовой фонд: <b>{tournament.prizePoolExc.toLocaleString()} EXC</b><br />
          {tournament.startDate && <>🔒 Закрытие регистрации: {tournament.startDate} (UTC)<br /></>}
          {tournament.endDate && <>⏰ Финиш: {tournament.endDate} (UTC)<br /></>}
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
        {tournament.entered && <div className="quest-status quest-status-approved" style={{ marginTop: 16 }}>✅ Вы зарегистрированы!</div>}
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
  const [searchParams] = useSearchParams();
  const initialSection = searchParams.get('section');
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

      {view === 'all' && <AllQuestsView expanded={expanded} details={details} onToggle={toggleExpand} onDetailChanged={loadDetail} initialSection={initialSection} />}
      {view === 'mine' && <MyQuestsView expanded={expanded} details={details} onToggle={toggleExpand} onDetailChanged={loadDetail} />}
      {view === 'tournament' && <TournamentView />}
    </div>
  );
}
