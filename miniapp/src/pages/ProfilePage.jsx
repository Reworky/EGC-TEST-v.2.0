import { useEffect, useState } from 'react';
import { getProfile, getAvatarUrl } from '../api/client';
import fireFrame from '../assets/frames/fire.png';
import './ProfilePage.css';

const LEVEL_COLORS = {
  'Новичок': '#8b95a8',
  'Игрок': '#34d399',
  'Опытный': '#38bdf8',
  'Ветеран': '#c084fc',
  'Легенда': '#fbbf24',
};

const FRAME_IMAGES = { fire: fireFrame };

export default function ProfilePage() {
  const [profile, setProfile] = useState(null);
  const [avatarUrl, setAvatarUrl] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    getProfile()
      .then(setProfile)
      .catch(() => setError('Профиль не найден. Зарегистрируйтесь через бота.'));
  }, []);

  useEffect(() => {
    if (!profile?.hasAvatar) return;
    let objectUrl;
    getAvatarUrl()
      .then(url => { objectUrl = url; setAvatarUrl(url); })
      .catch(() => setAvatarUrl(null));
    return () => { if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [profile?.hasAvatar]);

  if (error) return <div className="page-center error-msg">{error}</div>;
  if (!profile) return <div className="page-center">Загрузка...</div>;

  const xpIntoLevel = profile.xp % 1000;
  const xpProgress = xpIntoLevel / 10;
  const levelColor = LEVEL_COLORS[profile.levelName] || '#a855f7';
  const ringColor = profile.avatarFrameColor || levelColor;
  const frameImage = FRAME_IMAGES[profile.avatarFrameImage];

  return (
    <div className="profile-page">
      <div className="profile-glow" style={{ background: ringColor }} />

      <div className="profile-header">
        <div className="avatar-wrap">
          <div
            className="avatar"
            style={{
              '--ring-color': ringColor,
              background: avatarUrl ? 'transparent' : levelColor,
              borderColor: frameImage ? 'transparent' : ringColor,
            }}
          >
            {avatarUrl
              ? <img className="avatar-img" src={avatarUrl} alt="" />
              : (profile.nickname?.[0]?.toUpperCase() || '?')}
          </div>
          {frameImage && <img className="avatar-frame-img" src={frameImage} alt="" />}
        </div>
        <div className="profile-info">
          <h2 className="nickname">{profile.nickname}</h2>
          <span className="level-badge" style={{ background: levelColor }}>
            Ур. {profile.level} · {profile.levelName}
          </span>
        </div>
      </div>

      <div className="xp-card">
        <div className="xp-bar-labels">
          <span>{xpIntoLevel} / 1000 XP</span>
          <span>{profile.xp.toLocaleString()} всего</span>
        </div>
        <div className="xp-bar">
          <div className="xp-fill" style={{ width: `${xpProgress}%` }} />
        </div>
      </div>

      <div className="stats-grid">
        <div className="stat-card">
          <div className="stat-icon">💰</div>
          <div className="stat-value">{profile.coins.toLocaleString()}</div>
          <div className="stat-label">EXC</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon">🎯</div>
          <div className="stat-value">{profile.completedQuests}</div>
          <div className="stat-label">Квестов</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon">🔥</div>
          <div className="stat-value">{profile.streakDays}</div>
          <div className="stat-label">Серия дней</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon">💳</div>
          <div className="stat-value">{profile.remainingWithdrawalLimit?.toLocaleString() || '—'}</div>
          <div className="stat-label">Лимит вывода</div>
        </div>
      </div>

      {(profile.country || profile.platformsCsv) && (
        <div className="detail-card">
          {profile.country && (
            <div className="detail-row">
              <span className="detail-label">🌍 Страна</span>
              <span className="detail-value">{profile.country}</span>
            </div>
          )}
          {profile.platformsCsv && (
            <div className="detail-row">
              <span className="detail-label">🎮 Платформы</span>
              <span className="detail-value">{profile.platformsCsv}</span>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
