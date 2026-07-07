import { useEffect, useState } from 'react';
import { getProfile } from '../api/client';
import './ProfilePage.css';

const LEVEL_COLORS = {
  'Новичок': '#78909c',
  'Игрок': '#66bb6a',
  'Опытный': '#42a5f5',
  'Ветеран': '#ab47bc',
  'Легенда': '#ffa726',
};

export default function ProfilePage() {
  const [profile, setProfile] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    getProfile()
      .then(setProfile)
      .catch(() => setError('Профиль не найден. Зарегистрируйтесь через бота.'));
  }, []);

  if (error) return <div className="page-center error-msg">{error}</div>;
  if (!profile) return <div className="page-center">Загрузка...</div>;

  const xpProgress = (profile.xp % 1000) / 10;
  const levelColor = LEVEL_COLORS[profile.levelName] || '#6c63ff';

  return (
    <div className="profile-page">
      <div className="profile-header">
        <div className="avatar" style={{ background: levelColor }}>
          {profile.nickname?.[0]?.toUpperCase() || '?'}
        </div>
        <div className="profile-info">
          <h2 className="nickname">{profile.nickname}</h2>
          <span className="level-badge" style={{ background: levelColor }}>
            {profile.levelName}
          </span>
        </div>
      </div>

      <div className="xp-bar-wrap">
        <div className="xp-bar-labels">
          <span>{profile.xp} XP</span>
          <span>Ур. {profile.level}</span>
        </div>
        <div className="xp-bar">
          <div className="xp-fill" style={{ width: `${xpProgress}%`, background: levelColor }} />
        </div>
      </div>

      <div className="stats-grid">
        <div className="stat-card">
          <div className="stat-value">{profile.coins.toLocaleString()}</div>
          <div className="stat-label">EXC</div>
        </div>
        <div className="stat-card">
          <div className="stat-value">{profile.completedQuests}</div>
          <div className="stat-label">Квестов</div>
        </div>
        <div className="stat-card">
          <div className="stat-value">{profile.streakDays}</div>
          <div className="stat-label">Серия дней</div>
        </div>
        <div className="stat-card">
          <div className="stat-value">{profile.remainingWithdrawalLimit?.toLocaleString() || '—'}</div>
          <div className="stat-label">Лимит вывода</div>
        </div>
      </div>

      {profile.country && (
        <div className="detail-row">
          <span className="detail-label">Страна</span>
          <span className="detail-value">{profile.country}</span>
        </div>
      )}
      {profile.platformsCsv && (
        <div className="detail-row">
          <span className="detail-label">Платформы</span>
          <span className="detail-value">{profile.platformsCsv}</span>
        </div>
      )}
    </div>
  );
}
