import { useEffect, useState } from 'react';
import { getReferrals, getReferralRanking } from '../api/client';
import BackButton from '../components/BackButton';
import AnimatedNumber from '../components/AnimatedNumber';
import './ReferralsPage.css';

function formatCountdown(endsAtIso) {
  const diff = new Date(endsAtIso).getTime() - Date.now();
  if (diff <= 0) return null;
  const totalSeconds = Math.floor(diff / 1000);
  const h = String(Math.floor(totalSeconds / 3600)).padStart(2, '0');
  const m = String(Math.floor((totalSeconds % 3600) / 60)).padStart(2, '0');
  const s = String(totalSeconds % 60).padStart(2, '0');
  return `${h}:${m}:${s}`;
}

function daysWord(n) {
  const m10 = n % 10, m100 = n % 100;
  if (m100 >= 11 && m100 <= 19) return 'дней';
  return m10 === 1 ? 'день' : (m10 >= 2 && m10 <= 4 ? 'дня' : 'дней');
}

function fallbackCopy(text, onDone) {
  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.style.position = 'fixed';
  textarea.style.opacity = '0';
  document.body.appendChild(textarea);
  textarea.focus();
  textarea.select();
  try { document.execCommand('copy'); onDone(); } catch {}
  document.body.removeChild(textarea);
}

export default function ReferralsPage() {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [copied, setCopied] = useState(false);
  const [ranking, setRanking] = useState(null);
  const [countdown, setCountdown] = useState(null);
  const [shownEarned, setShownEarned] = useState(0);

  useEffect(() => {
    getReferrals().then(setData).catch(() => setError('Не удалось загрузить данные. Попробуйте ещё раз.'));
    getReferralRanking().then(setRanking).catch(() => setRanking(null));
  }, []);

  // Счётчик «заработано с рефералов» разгоняется от 0 до итога при открытии страницы.
  useEffect(() => {
    if (data) setShownEarned(data.earnedExc);
  }, [data]);

  useEffect(() => {
    if (!data?.boostActive || !data?.boostEndsAt) { setCountdown(null); return; }
    const tick = () => setCountdown(formatCountdown(data.boostEndsAt));
    tick();
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [data?.boostActive, data?.boostEndsAt]);

  function copyLink() {
    const markCopied = () => { setCopied(true); setTimeout(() => setCopied(false), 2000); };
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(data.referralLink).then(markCopied, () => fallbackCopy(data.referralLink, markCopied));
    } else {
      fallbackCopy(data.referralLink, markCopied);
    }
  }

  function shareLink() {
    const tg = window.Telegram?.WebApp;
    if (tg?.openTelegramLink) tg.openTelegramLink(data.shareUrl);
    else window.open(data.shareUrl, '_blank');
  }

  if (error) return <div style={{ padding: 32, color: '#ef4444', textAlign: 'center' }}>{error}</div>;
  if (!data) return <div style={{ padding: 32, color: '#888', textAlign: 'center' }}>Загрузка...</div>;

  const pct = Math.min(data.progressPercent, 100);

  return (
    <div className="ref-page">
      <div style={{ padding: '16px 16px 0' }}><BackButton to="/profile" label="Профиль" /></div>

      {data.boostActive && countdown && (
        <div className="ref-boost-banner">
          🚀 Буст-уикенд ×{data.boostMultiplier}! Мгновенная награда за друга умножена
          <div className="ref-boost-banner-timer">Осталось: {countdown}</div>
        </div>
      )}

      <div className="ref-hero ref-earn">
        <div className="ref-earn-label">Заработано с рефералов</div>
        <div className="ref-earn-value">
          <AnimatedNumber value={shownEarned} duration={1.6} />
          <span className="ref-earn-unit">EXC</span>
        </div>
        <div className="ref-earn-chip">👥 Приглашено друзей: <b>{data.invitedFriends}</b></div>
      </div>

      {data.goalOpen && (
        <div className="ref-goal">
          <div className="ref-goal-head">
            <div className="ref-goal-icon">🎯</div>
            <div className="ref-goal-titles">
              <div className="ref-goal-title">Позови друга</div>
              <div className="ref-goal-sub">
                {data.goalFriendJoined ? 'Друг в клубе, ждём его первый квест' : 'Награда за первого друга'}
              </div>
            </div>
          </div>

          <div className="ref-goal-steps">
            <div className={`ref-goal-step${data.goalFriendJoined ? ' done' : ' active'}`}>
              <span className="ref-goal-dot">{data.goalFriendJoined ? '✓' : '1'}</span>Друг в клубе
            </div>
            <div className="ref-goal-line" />
            <div className={`ref-goal-step${data.goalFriendJoined ? ' active' : ''}`}>
              <span className="ref-goal-dot">2</span>Его первый квест
            </div>
          </div>

          <div className="ref-goal-rewards">
            {data.goalPassReward && (
              <span className="ref-goal-chip ref-goal-chip-pass">🎁 {data.goalPassDays} {daysWord(data.goalPassDays)} EGC Pass</span>
            )}
            <span className="ref-goal-chip ref-goal-chip-exc">+2 500 EXC</span>
            {!data.goalFriendJoined && <span className="ref-goal-chip ref-goal-chip-friend">🤝 Другу +3 500 EXC</span>}
          </div>

          {!data.goalFriendJoined && (
            <button className="ref-btn ref-btn-primary ref-goal-btn" onClick={shareLink}>📣 Позвать друга</button>
          )}
        </div>
      )}

      <div className="ref-link-card">
        <div className="ref-link-label">Ваша реферальная ссылка</div>
        <div className="ref-link-value">{data.referralLink}</div>
        <div className="ref-link-actions">
          <button className="ref-btn ref-btn-primary" onClick={copyLink}>
            {copied ? '✅ Скопировано' : '📋 Копировать'}
          </button>
          <button className="ref-btn ref-btn-secondary" onClick={shareLink}>
            📤 Поделиться
          </button>
        </div>
      </div>

      <div className="ref-progress-card">
        <div className="ref-progress-label">Прогресс до {data.nextMilestone.toLocaleString()} EXC</div>
        <div className="ref-progress-track">
          <div className="ref-progress-fill" style={{ width: pct + '%' }} />
        </div>
        <div className="ref-progress-pct">{pct}%</div>
      </div>

      <div className="ref-progress-card">
        {data.currentFriendBadge && (
          <div className="ref-friend-badge-current">{data.currentFriendBadge}</div>
        )}
        {data.nextFriendMilestone ? (
          <>
            <div className="ref-progress-label">Прогресс до следующего бейджа ({data.nextFriendMilestone} друзей)</div>
            <div className="ref-progress-track">
              <div className="ref-progress-fill" style={{ width: data.friendProgressPercent + '%' }} />
            </div>
            <div className="ref-progress-pct">{data.friendProgressPercent}%</div>
          </>
        ) : (
          <div className="ref-progress-label">🏆 Все бейджи за друзей получены!</div>
        )}
      </div>

      {ranking && (ranking.top?.length > 0) && (
        <div className="ref-rank-card">
          <div className="ref-rank-title">🏆 Рейтинг рефереров за неделю</div>
          {ranking.top.map(entry => (
            <div key={entry.rank} className={`ref-rank-row${entry.isMe ? ' ref-rank-row-me' : ''}`}>
              <span className="ref-rank-num">{entry.rank}.</span>
              <span className="ref-rank-name">{entry.nickname || 'Игрок'}</span>
              <span className="ref-rank-invited">👥 {entry.invitedFriends}</span>
              <span className="ref-rank-exc">+{entry.weeklyExc.toLocaleString()} EXC</span>
              {entry.isMe && <span className="ref-rank-me-tag">👈</span>}
            </div>
          ))}
          {ranking.yourEntry && (
            <div className="ref-rank-you">
              {ranking.yourEntry.rank}. {ranking.yourEntry.nickname || 'Ты'} - ваше место
            </div>
          )}
          <div className="ref-rank-footer">📅 Топ-5 в конце недели (в понедельник) получит бонус из пула 2000 EXC.</div>
        </div>
      )}

      <div className="ref-how-card">
        <div className="ref-how-title">Как это работает</div>

        <div className="ref-step">
          <div className="ref-step-num">1</div>
          <div className="ref-step-body">
            <div className="ref-step-title">Друг вступает в клуб</div>
            <div className="ref-step-desc">Тебе: <b>+300 EXC</b> · Другу: <b>+500 EXC</b></div>
          </div>
        </div>

        <div className="ref-step">
          <div className="ref-step-num">2</div>
          <div className="ref-step-body">
            <div className="ref-step-title">Друг выполняет первый квест</div>
            <div className="ref-step-desc">Тебе бонусом: <b>+2 500 EXC</b> · Другу бонусом: <b>+3 000 EXC</b></div>
          </div>
        </div>

        <div className="ref-step">
          <div className="ref-step-num">3</div>
          <div className="ref-step-body">
            <div className="ref-step-title">Друг зарабатывает квестами</div>
            <div className="ref-step-desc">Ты получаешь <b>10% от каждого его EXC</b> — пока друг активен (квест хотя бы раз в 14 дней)</div>
          </div>
        </div>

        <div className="ref-how-footer">Скопируй ссылку и отправь другу — остальное система сделает сама.</div>
      </div>

    </div>
  );
}
