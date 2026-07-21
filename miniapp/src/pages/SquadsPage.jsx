import { useEffect, useState } from 'react';
import { getMySquad, createSquad, joinSquad, leaveSquad, disbandSquad, kickSquadMember, getSquadLeaderboard } from '../api/client';
import BackButton from '../components/BackButton';
import './QuestsPage.css';
import './ShopPage.css';
import './ReferralsPage.css';

const MEDALS = ['🥇', '🥈', '🥉'];

function SquadCard({ squad, onChanged }) {
  const [busy, setBusy] = useState(false);
  const [kickTarget, setKickTarget] = useState(null);
  const botUsername = 'invitetogamebot';
  const inviteLink = `https://t.me/${botUsername}?start=squad_${squad.inviteCode}`;

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

  function copyInvite() {
    navigator.clipboard?.writeText(inviteLink).catch(() => {});
  }

  return (
    <div className="ref-link-card" style={{ margin: '12px 16px' }}>
      <div className="ref-link-label">⚔️ {squad.name}</div>
      <p className="shop-desc">
        Участников: <b>{squad.members.length}</b> · XP за неделю: <b>{squad.weeklyXp.toLocaleString()}</b>
      </p>

      <div className="category-section" style={{ marginTop: 8 }}>
        {squad.members.map(m => (
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
      </div>

      <div style={{ marginTop: 12, display: 'flex', flexDirection: 'column', gap: 8 }}>
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
    <div className="ref-link-card" style={{ margin: '12px 16px', textAlign: 'center' }}>
      <div className="ref-link-label">⚔️ Ты не состоишь в отряде</div>
      <p className="shop-desc">Создай свой отряд или вступи по коду приглашения.</p>
      <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
        <button className="quest-btn" style={{ flex: 1 }} onClick={() => setMode('create')}>➕ Создать</button>
        <button className="quest-btn quest-btn-secondary" style={{ flex: 1 }} onClick={() => setMode('join')}>🔗 Вступить</button>
      </div>
    </div>
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

function LeaderboardView() {
  const [entries, setEntries] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    getSquadLeaderboard().then(setEntries).catch(() => setError('Не удалось загрузить рейтинг.'));
  }, []);

  if (error) return <div className="page-center error-msg">{error}</div>;
  if (!entries) return <div className="page-center">Загрузка...</div>;
  if (entries.length === 0) return <div className="page-center">Рейтинг отрядов пуст.</div>;

  return (
    <div className="category-section">
      {entries.map(e => (
        <div key={e.rank} className="shop-card" style={{ padding: '10px 14px' }}>
          <div className="shop-top">
            <div className="shop-title">
              {e.rank <= 3 ? MEDALS[e.rank - 1] : `#${e.rank}`} {e.name}
            </div>
            <div className="shop-price" style={{ fontSize: 13 }}>{e.weeklyXp.toLocaleString()} XP</div>
          </div>
          <div className="shop-meta"><span style={{ opacity: 0.6 }}>Участников: {e.memberCount}</span></div>
        </div>
      ))}
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

      {tab === 'lb' && <LeaderboardView />}
    </div>
  );
}
