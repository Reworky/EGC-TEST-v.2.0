import { useEffect, useState } from 'react';
import { getMySquad, createSquad, joinSquad, leaveSquad, disbandSquad, kickSquadMember, getSquadLeaderboard, getSquadOverallLeaderboard, getSquadCatalog, joinOpenSquad, setSquadRecruitment, getSquadFlagUrl, getSquadView, requestJoinSquad, getSquadRequests, decideSquadRequest } from '../api/client';
import BackButton from '../components/BackButton';
import './QuestsPage.css';
import './ShopPage.css';
import './ReferralsPage.css';

const MEDALS = ['🥇', '🥈', '🥉'];

function SquadFlag({ squadId, hasFlag }) {
  const [url, setUrl] = useState(null);

  useEffect(() => {
    if (!hasFlag) { setUrl(null); return undefined; }
    let alive = true;
    let objectUrl = null;
    getSquadFlagUrl(squadId).then(u => {
      if (alive) { objectUrl = u; setUrl(u); } else { URL.revokeObjectURL(u); }
    }).catch(() => {});
    return () => { alive = false; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [squadId, hasFlag]);

  if (!url) return null;
  return (
    <img src={url} alt="Флаг отряда"
      style={{ width: '100%', maxHeight: 180, objectFit: 'cover', borderRadius: 12, margin: '4px 0 10px' }} />
  );
}

function SquadGoal({ squad }) {
  // Старый бэкенд (до деплоя цели недели) полей цели не присылает — тогда блок не показываем.
  if (squad.goalTarget === undefined) return null;
  const pct = squad.goalTarget > 0 ? Math.min(100, Math.round((squad.goalDone / squad.goalTarget) * 100)) : 0;
  const left = (squad.goalMinMembers || 3) - squad.members.length;
  const streak = squad.streakDays || 0;
  return (
    <div style={{ margin: '4px 0 10px' }}>
      {squad.goalEligible ? (
        <>
          <p className="shop-desc" style={{ marginBottom: 4 }}>
            🎯 Цель недели: <b>{squad.goalDone} / {squad.goalTarget}</b> квестов
          </p>
          <div className="quest-progress-track">
            <div className="quest-progress-fill" style={{ width: `${pct}%` }} />
          </div>
          <p className="shop-desc" style={{ marginTop: 4, opacity: 0.8 }}>
            {squad.goalReached
              ? `✅ Цель выполнена! В понедельник каждый участник с квестом получит +${squad.goalBonus} EXC`
              : `🎁 Выполните цель — каждый участник с квестом получит +${squad.goalBonus} EXC в понедельник`}
          </p>
        </>
      ) : (
        <p className="shop-desc">
          🎯 Командная цель недели откроется при {squad.goalMinMembers} участниках — ещё {Math.max(left, 1)}
        </p>
      )}
      {streak > 0 && (
        <p className="shop-desc" style={{ marginTop: 4 }}>
          🔥 Серия отряда: <b>{streak}</b> {streak === 1 ? 'день' : streak < 5 ? 'дня' : 'дней'} подряд
        </p>
      )}
    </div>
  );
}

function SquadCatalog({ onChanged }) {
  const [entries, setEntries] = useState(null);
  const [query, setQuery] = useState('');
  const [busyId, setBusyId] = useState(null);
  const [error, setError] = useState(null);

  function load(q) {
    getSquadCatalog(q).then(setEntries).catch(() => setEntries([]));
  }

  // Поиск по названию: небольшая задержка, чтобы не слать запрос на каждую букву.
  useEffect(() => {
    const t = setTimeout(() => load(query.trim() || undefined), 300);
    return () => clearTimeout(t);
  }, [query]);

  async function handleJoin(e) {
    setBusyId(e.id); setError(null);
    try {
      if (e.open !== false) {
        await joinOpenSquad(e.id);
        onChanged();
      } else {
        await requestJoinSquad(e.id);
        setEntries(prev => prev.map(x => x.id === e.id ? { ...x, requested: true } : x));
        setBusyId(null);
      }
    } catch (err) {
      setError(err?.response?.data?.message || 'Не удалось выполнить действие.');
      setBusyId(null);
    }
  }

  return (
    <div className="ref-link-card" style={{ margin: '12px 16px' }}>
      <div className="ref-link-label">🔎 Найти отряд</div>
      <input className="quest-text-input" placeholder="Поиск по названию отряда"
        value={query} onChange={e => setQuery(e.target.value)} maxLength={30} />
      {entries === null ? <p className="shop-desc">Загрузка...</p>
        : entries.length === 0 ? (
          <p className="shop-desc">{query.trim() ? 'Ничего не найдено — попробуй другое название.' : 'Отрядов пока нет — создай свой, и он сразу появится здесь.'}</p>
        ) : (
          <div className="category-section" style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 8 }}>
            {entries.map(e => (
              <div key={e.id} className="shop-card" style={{ padding: '10px 14px' }}>
                <div className="shop-top">
                  <div className="shop-title">{e.open !== false ? '🔓' : '🔒'} {e.name}</div>
                  <div className="shop-price" style={{ fontSize: 12 }}>{e.weeklyXp.toLocaleString()} XP</div>
                </div>
                <div className="shop-meta"><span style={{ opacity: 0.6 }}>Участников: {e.memberCount}</span></div>
                <button className={`quest-btn ${e.open !== false || e.requested ? '' : 'quest-btn-secondary'}`} style={{ marginTop: 6 }}
                  disabled={busyId !== null || e.requested}
                  onClick={() => handleJoin(e)}>
                  {busyId === e.id ? 'Секунду...' : e.requested ? '⏳ Заявка отправлена' : e.open !== false ? '➡️ Вступить' : '📨 Подать заявку'}
                </button>
              </div>
            ))}
          </div>
        )}
      {error && <div className="quest-message" style={{ color: '#f87171' }}>{error}</div>}
    </div>
  );
}

function SquadRequests({ onChanged }) {
  const [requests, setRequests] = useState(null);
  const [busyId, setBusyId] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => { getSquadRequests().then(setRequests).catch(() => setRequests([])); }, []);

  async function decide(id, accept) {
    setBusyId(id); setError(null);
    try {
      await decideSquadRequest(id, accept);
      if (accept) { onChanged(); return; }
      setRequests(prev => prev.filter(r => r.id !== id));
    } catch (err) {
      setError(err?.response?.data?.message || 'Не удалось обработать заявку.');
    } finally { setBusyId(null); }
  }

  if (!requests || requests.length === 0) return null;
  return (
    <div style={{ margin: '8px 0 12px' }}>
      <p className="shop-desc" style={{ marginBottom: 6 }}>📨 <b>Заявки в отряд ({requests.length})</b></p>
      {requests.map(r => (
        <div key={r.id} className="shop-card" style={{ padding: '10px 14px', marginBottom: 8 }}>
          <div className="shop-top">
            <div className="shop-title">{r.nickname}</div>
            <div className="shop-price" style={{ fontSize: 12 }}>{r.xp.toLocaleString()} XP</div>
          </div>
          <div className="shop-meta"><span style={{ opacity: 0.6 }}>{r.levelName}</span></div>
          <div style={{ display: 'flex', gap: 8, marginTop: 6 }}>
            <button className="quest-btn" style={{ flex: 1 }} disabled={busyId !== null} onClick={() => decide(r.id, true)}>✅ Принять</button>
            <button className="quest-btn quest-btn-secondary" style={{ flex: 1 }} disabled={busyId !== null} onClick={() => decide(r.id, false)}>❌ Отклонить</button>
          </div>
        </div>
      ))}
      {error && <div className="quest-message" style={{ color: '#f87171' }}>{error}</div>}
    </div>
  );
}

function SquadCard({ squad, onChanged }) {
  const [busy, setBusy] = useState(false);
  const [kickTarget, setKickTarget] = useState(null);
  const inviteLink = squad.inviteLink;

  async function handleLeave() {
    if (!confirm('Покинуть отряд?')) return;
    setBusy(true);
    try { await leaveSquad(); onChanged(); } finally { setBusy(false); }
  }

  async function handleDisband() {
    if (!confirm('Расформировать отряд? Это действие необратимо.')) return;
    setBusy(true);
    try { await disbandSquad(); onChanged(); } finally { setBusy(false); }
  }

  async function handleKick(memberTelegramId) {
    setBusy(true);
    try { await kickSquadMember(memberTelegramId); onChanged(); } finally {
      setBusy(false);
      setKickTarget(null);
    }
  }

  async function handleToggleRecruitment() {
    setBusy(true);
    try { await setSquadRecruitment(!squad.openRecruitment); onChanged(); } finally { setBusy(false); }
  }

  function copyInvite() {
    navigator.clipboard?.writeText(inviteLink).catch(() => {});
  }

  const sortedMembers = [...squad.members].sort((a, b) => b.weeklyXp - a.weeklyXp);
  const MAX_SHOWN = 30;
  const shownMembers = sortedMembers.slice(0, MAX_SHOWN);
  const hiddenCount = sortedMembers.length - shownMembers.length;

  return (
    <div className="ref-link-card" style={{ margin: '12px 16px' }}>
      <SquadFlag squadId={squad.id} hasFlag={squad.hasFlag} />
      <div className="ref-link-label">⚔️ {squad.name}</div>
      <p className="shop-desc">
        Участников: <b>{squad.members.length}</b> · Рейтинг за неделю: <b>{squad.weeklyXp.toLocaleString()}</b>
      </p>
      <SquadGoal squad={squad} />
      {squad.isCaptain && squad.pendingRequests > 0 && <SquadRequests onChanged={onChanged} />}
      {squad.weeklyBonusPoints > 0 && (
        <p className="shop-desc" style={{ marginTop: -8 }}>
          🎉 Бонус за рефералов: <b>+{squad.weeklyBonusPoints.toLocaleString()}</b>
        </p>
      )}

      <div className="category-section" style={{ marginTop: 8 }}>
        {shownMembers.map(m => (
          <div key={m.telegramId} className="shop-card" style={{ padding: '10px 14px' }}>
            <div className="shop-top">
              <div className="shop-title">
                {m.isCaptain ? '👑 ' : ''}{m.nickname}
              </div>
              <div className="shop-price" style={{ fontSize: 12 }}>+{m.weeklyXp.toLocaleString()} XP</div>
            </div>
            <div className="shop-meta"><span style={{ opacity: 0.6 }}>{m.levelName}</span></div>
            {squad.isCaptain && !m.isCaptain && (
              kickTarget === m.telegramId ? (
                <div style={{ display: 'flex', gap: 8, marginTop: 6 }}>
                  <button className="quest-btn quest-btn-secondary" style={{ flex: 1 }}
                    disabled={busy} onClick={() => handleKick(m.telegramId)}>Исключить</button>
                  <button className="quest-btn" style={{ flex: 1 }}
                    onClick={() => setKickTarget(null)}>Отмена</button>
                </div>
              ) : (
                <button className="quest-btn quest-btn-secondary" style={{ marginTop: 6 }}
                  onClick={() => setKickTarget(m.telegramId)}>👢 Исключить</button>
              )
            )}
          </div>
        ))}
        {hiddenCount > 0 && (
          <div className="shop-desc" style={{ textAlign: 'center', opacity: 0.6, padding: '6px 0' }}>
            …и ещё {hiddenCount} участников
          </div>
        )}
      </div>

      <div style={{ marginTop: 12, display: 'flex', flexDirection: 'column', gap: 8 }}>
        {squad.isCaptain && (
          <button className="quest-btn quest-btn-secondary" disabled={busy} onClick={handleToggleRecruitment}>
            {squad.openRecruitment ? '🔓 Набор открыт — закрыть' : '🔒 Набор закрыт — открыть для всех'}
          </button>
        )}
        <div className="ref-link-box" style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          <span style={{ flex: 1, fontSize: 12, opacity: 0.7, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            Код: {squad.inviteCode}
          </span>
          <button className="quest-btn" style={{ padding: '6px 14px', fontSize: 13 }} onClick={copyInvite}>
            📋 Скопировать
          </button>
        </div>
        {squad.isCaptain
          ? <button className="quest-btn quest-btn-secondary" disabled={busy} onClick={handleDisband}>
              🔴 Расформировать отряд
            </button>
          : <button className="quest-btn quest-btn-secondary" disabled={busy} onClick={handleLeave}>
              🚪 Покинуть отряд
            </button>
        }
      </div>
    </div>
  );
}

function NoSquadView({ onChanged }) {
  const [mode, setMode] = useState(null); // 'create' | 'join'
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  async function handleCreate() {
    if (!name.trim()) { setError('Введите название отряда.'); return; }
    setBusy(true); setError(null);
    try { await createSquad(name.trim()); onChanged(); }
    catch (e) { setError(e?.response?.data?.message || 'Ошибка создания отряда.'); }
    finally { setBusy(false); }
  }

  async function handleJoin() {
    if (!code.trim()) { setError('Введите код отряда.'); return; }
    setBusy(true); setError(null);
    try { await joinSquad(code.trim()); onChanged(); }
    catch (e) { setError(e?.response?.data?.message || 'Код не найден или отряд недоступен.'); }
    finally { setBusy(false); }
  }

  if (!mode) return (
    <>
      <div className="ref-link-card" style={{ margin: '12px 16px', textAlign: 'center' }}>
        <div className="ref-link-label">⚔️ Ты не состоишь в отряде</div>
        <p className="shop-desc">Вступи в открытый отряд из каталога, создай свой или войди по коду приглашения. От 3 участников у отряда появляется командная цель недели с наградой.</p>
        <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
          <button className="quest-btn" style={{ flex: 1 }} onClick={() => setMode('create')}>➕ Создать</button>
          <button className="quest-btn quest-btn-secondary" style={{ flex: 1 }} onClick={() => setMode('join')}>🔗 По коду</button>
        </div>
      </div>
      <SquadCatalog onChanged={onChanged} />
    </>
  );

  return (
    <div className="ref-link-card" style={{ margin: '12px 16px' }}>
      <div className="ref-link-label">{mode === 'create' ? '➕ Создать отряд' : '🔗 Вступить по коду'}</div>
      {mode === 'create'
        ? <input className="quest-text-input" placeholder="Название отряда (до 30 символов)"
            value={name} onChange={e => setName(e.target.value)} maxLength={30} />
        : <input className="quest-text-input" placeholder="Код приглашения"
            value={code} onChange={e => setCode(e.target.value)} />
      }
      {error && <div className="quest-message" style={{ color: '#f87171' }}>{error}</div>}
      <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
        <button className="quest-btn" style={{ flex: 1 }} disabled={busy}
          onClick={mode === 'create' ? handleCreate : handleJoin}>
          {busy ? 'Секунду...' : 'Подтвердить'}
        </button>
        <button className="quest-btn quest-btn-secondary" style={{ flex: 1 }}
          onClick={() => { setMode(null); setError(null); }}>Назад</button>
      </div>
    </div>
  );
}

function SquadPublic({ squadId, onBack, onJoined }) {
  const [data, setData] = useState(undefined); // undefined=загрузка, null=не найден
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    getSquadView(squadId).then(setData).catch(() => setData(null));
  }, [squadId]);

  async function handleJoin() {
    setBusy(true); setError(null);
    try {
      if (data.open) { await joinOpenSquad(squadId); onJoined(); return; }
      await requestJoinSquad(squadId);
      setData({ ...data, requested: true });
    } catch (e) {
      setError(e?.response?.data?.message || 'Не удалось выполнить действие.');
    } finally { setBusy(false); }
  }

  return (
    <div className="ref-link-card" style={{ margin: '12px 16px' }}>
      <button className="quest-btn quest-btn-secondary" style={{ marginBottom: 8 }} onClick={onBack}>⬅️ К рейтингу</button>
      {data === undefined ? <p className="shop-desc">Загрузка...</p>
        : data === null ? <p className="shop-desc">Отряд не найден или уже расформирован.</p>
        : (
          <>
            <SquadFlag squadId={data.id} hasFlag={data.hasFlag} />
            <div className="ref-link-label">⚔️ {data.name}</div>
            <p className="shop-desc">
              {data.open ? '🔓 Набор открыт' : '🔒 Набор закрыт — вступить по заявке'}<br />
              Участников: <b>{data.memberCount}</b> · XP за неделю: <b>{data.weeklyXp.toLocaleString()}</b>
              {data.streakDays > 0 && <><br />🔥 Серия: <b>{data.streakDays}</b> {data.streakDays === 1 ? 'день' : data.streakDays < 5 ? 'дня' : 'дней'} подряд</>}
            </p>
            <div className="category-section" style={{ marginTop: 8 }}>
              {data.topMembers.map((m, i) => (
                <div key={i} className="shop-card" style={{ padding: '8px 14px' }}>
                  <div className="shop-top">
                    <div className="shop-title">{m.isCaptain ? '👑 ' : ''}{m.nickname}</div>
                    <div className="shop-price" style={{ fontSize: 12 }}>+{m.weeklyXp.toLocaleString()} XP</div>
                  </div>
                  <div className="shop-meta"><span style={{ opacity: 0.6 }}>{m.levelName}</span></div>
                </div>
              ))}
            </div>
            {error && <div className="quest-message" style={{ color: '#f87171' }}>{error}</div>}
            {data.mine ? <p className="shop-desc" style={{ marginTop: 8 }}>Это твой отряд.</p>
              : !data.viewerHasSquad && (
                <button className={`quest-btn ${data.open ? '' : 'quest-btn-secondary'}`} style={{ marginTop: 10 }}
                  disabled={busy || data.requested} onClick={handleJoin}>
                  {busy ? 'Секунду...' : data.requested ? '⏳ Заявка отправлена' : data.open ? '➡️ Вступить в отряд' : '📨 Подать заявку'}
                </button>
              )}
          </>
        )}
    </div>
  );
}

function LeaderboardView({ onJoined }) {
  const [period, setPeriod] = useState('week'); // 'week' | 'overall'
  const [entries, setEntries] = useState(null);
  const [error, setError] = useState(null);
  const [selected, setSelected] = useState(null);

  useEffect(() => {
    setEntries(null);
    setError(null);
    const fetcher = period === 'week' ? getSquadLeaderboard : getSquadOverallLeaderboard;
    fetcher().then(setEntries).catch(() => setError('Не удалось загрузить рейтинг.'));
  }, [period]);

  if (selected) {
    return <SquadPublic squadId={selected} onBack={() => setSelected(null)} onJoined={onJoined} />;
  }

  return (
    <div>
      <div className="view-toggle" style={{ margin: '12px 16px 0' }}>
        <button className={`view-tab ${period === 'week' ? 'active' : ''}`} onClick={() => setPeriod('week')}>Неделя</button>
        <button className={`view-tab ${period === 'overall' ? 'active' : ''}`} onClick={() => setPeriod('overall')}>Общий</button>
      </div>

      {error ? <div className="page-center error-msg">{error}</div>
        : !entries ? <div className="page-center">Загрузка...</div>
        : entries.length === 0 ? <div className="page-center">Рейтинг отрядов пуст.</div>
        : (
          <div className="category-section" style={{ padding: '12px 16px', display: 'flex', flexDirection: 'column', gap: 12 }}>
            {entries.map(e => (
              <div key={e.rank} className="shop-card" style={{ padding: '10px 14px', cursor: e.squadId ? 'pointer' : 'default' }}
                onClick={() => e.squadId && setSelected(e.squadId)}>
                <div className="shop-top">
                  <div className="shop-title">
                    {e.rank <= 3 ? MEDALS[e.rank - 1] : `#${e.rank}`} {e.name}
                  </div>
                  <div className="shop-price" style={{ fontSize: 13 }}>{e.xp.toLocaleString()} XP</div>
                </div>
                <div className="shop-meta"><span style={{ opacity: 0.6 }}>Участников: {e.memberCount}{e.squadId ? ' · нажми, чтобы открыть' : ''}</span></div>
              </div>
            ))}
          </div>
        )}
    </div>
  );
}

export default function SquadsPage() {
  const [tab, setTab] = useState('squad');
  const [squad, setSquad] = useState(undefined); // undefined=loading, null=no squad
  const [error, setError] = useState(null);

  function reload() {
    setError(null);
    setSquad(undefined);
    getMySquad()
      .then(data => setSquad(data))
      .catch(() => setError('Не удалось загрузить данные отряда.'));
  }

  useEffect(() => { reload(); }, []);

  return (
    <div className="quests-page shop-page">
      <div style={{ padding: '16px 16px 0', display: 'flex', alignItems: 'center', gap: 12 }}>
        <BackButton label="Назад" />
        <span style={{ fontSize: 20, fontWeight: 700 }}>⚔️ Отряды</span>
      </div>

      <div className="view-toggle">
        <button className={`view-tab ${tab === 'squad' ? 'active' : ''}`} onClick={() => setTab('squad')}>Мой отряд</button>
        <button className={`view-tab ${tab === 'lb' ? 'active' : ''}`} onClick={() => setTab('lb')}>🏆 Рейтинг</button>
      </div>

      {tab === 'squad' && (
        error ? <div className="page-center error-msg">{error}</div>
        : squad === undefined ? <div className="page-center">Загрузка...</div>
        : squad ? <SquadCard squad={squad} onChanged={reload} />
        : <NoSquadView onChanged={reload} />
      )}

      {tab === 'lb' && <LeaderboardView onJoined={() => { setTab('squad'); reload(); }} />}
    </div>
  );
}
