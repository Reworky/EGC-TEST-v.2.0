import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getProfile, getAvatarUrl } from '../api/client';
import fireFrame from '../assets/frames/fire.png';
import iceFrame from '../assets/frames/ice.png';
import purpleFrame from '../assets/frames/purple.png';
import goldFrame from '../assets/frames/gold.png';
import './ProfilePage.css';

// По гайду EGC_Rank_Design_Guide.md, адаптировано под тёмную тему приложения (в гайде фоны светлые).
// Тяжёлые эффекты гайда (SVG-паттерны на ранг, частицы, 3D-вращение, звук) сознательно не переносились —
// сам гайд помечает их опциональными, и для мобильного WebView это лишний вес без явной пользы.
const RANK_STYLES = {
  'Новичок': { icon: '🥚', primary: '#CCCCCC', secondary: '#999999', glow: 'none', pulse: false },
  'Игрок': { icon: '🎮', primary: '#00AA00', secondary: '#00DD00', glow: '0 0 10px rgba(0,170,0,0.3)', pulse: true },
  'Ветеран': { icon: '⚔️', primary: '#0066FF', secondary: '#0088FF', glow: '0 0 15px rgba(0,102,255,0.4)', pulse: true },
  'Элита': { icon: '💎', primary: '#9933FF', secondary: '#BB66FF', glow: '0 0 20px rgba(153,51,255,0.5)', pulse: true },
  'Легенда': { icon: '⭐', primary: '#FFCC00', secondary: '#FFDD33', glow: '0 0 25px rgba(255,204,0,0.6)', pulse: true },
  'Герой EXPERIENCE': { icon: '👑', primary: '#FF6600', secondary: '#FF8833', glow: '0 0 30px rgba(255,102,0,0.7), 0 0 15px rgba(255,51,0,0.5)', pulse: true },
  'Чемпион EXPERIENCE': { icon: '💠', primary: '#00DDDD', secondary: '#AAAAFF', glow: '0 0 35px rgba(0,221,221,0.6), 0 0 20px rgba(170,170,255,0.4)', pulse: true },
  'Амбассадор EXPERIENCE': {
    icon: '🚀', primary: '#FF7700', secondary: '#9933FF', rainbow: true,
    glow: '0 0 40px rgba(255,0,0,0.5), 0 0 30px rgba(255,127,0,0.4), 0 0 20px rgba(0,153,255,0.3), 0 0 10px rgba(153,51,255,0.2)',
    pulse: true,
  },
};
const DEFAULT_RANK = RANK_STYLES['Новичок'];

// Пороги XP по тем же уровням, что и на бэкенде (UserService.LEVEL_TIERS) — нужны, чтобы правильно
// посчитать прогресс до следующего ранга (раньше бар считался как xp % 1000, что не совпадало с
// реальными порогами уровней уже начиная со 2 ранга).
const TIER_THRESHOLDS = [0, 1000, 5000, 15000, 35000, 75000, 150000, 300000];

const FRAME_IMAGES = { fire: fireFrame, ice: iceFrame, purple: purpleFrame, gold: goldFrame };
// Разные картинки — разное соотношение "толщина рамки / отверстие", размер подобран под 84px аватар индивидуально
const FRAME_SIZES = { fire: 116, ice: 152, purple: 124, gold: 130 };

function hexToRgba(hex, alpha) {
  const n = parseInt(hex.replace('#', ''), 16);
  const r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

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

  const tierIndex = profile.level - 1;
  const tierStart = TIER_THRESHOLDS[tierIndex] ?? 0;
  const nextTierStart = TIER_THRESHOLDS[tierIndex + 1];
  const isMaxRank = nextTierStart === undefined;
  const xpIntoLevel = profile.xp - tierStart;
  const xpNeeded = isMaxRank ? 0 : nextTierStart - tierStart;
  const xpProgress = isMaxRank ? 100 : Math.min(100, (xpIntoLevel / xpNeeded) * 100);

  const rank = RANK_STYLES[profile.levelName] || DEFAULT_RANK;
  const ringColor = profile.avatarFrameColor || rank.primary;
  const frameImage = FRAME_IMAGES[profile.avatarFrameImage];
  const frameSize = FRAME_SIZES[profile.avatarFrameImage] || 128;
  const showRainbowRing = rank.rainbow && !frameImage;
  const badgeBg = rank.rainbow
    ? `linear-gradient(90deg, #FF0000, #FF7700, #FFFF00, #00FF00, #0099FF, #9933FF)`
    : rank.primary;

  return (
    <div
      className="profile-page"
      style={{ '--rank-blob-1': hexToRgba(rank.primary, 0.12 + tierIndex * 0.045), '--rank-blob-2': hexToRgba(rank.secondary, 0.1 + tierIndex * 0.04) }}
    >
      <div className="profile-glow" style={{ background: ringColor }} />

      <div className="profile-header">
        <div className="avatar-wrap">
          {showRainbowRing && <div className="rank-rainbow-ring" />}
          {rank.pulse && <div className="rank-glow-ring" style={{ boxShadow: rank.glow }} />}
          <div
            className="avatar"
            style={{
              '--ring-color': ringColor,
              background: avatarUrl ? 'transparent' : rank.primary,
              borderColor: frameImage ? 'transparent' : ringColor,
            }}
          >
            {avatarUrl
              ? <img className="avatar-img" src={avatarUrl} alt="" />
              : (profile.nickname?.[0]?.toUpperCase() || '?')}
          </div>
          {frameImage && (
            <img
              className="avatar-frame-img"
              src={frameImage}
              alt=""
              style={{ width: frameSize, height: frameSize }}
            />
          )}
        </div>
        <div className="profile-info">
          <h2 className="nickname">{profile.nickname}</h2>
          <span className="level-badge" style={{ background: badgeBg, boxShadow: `0 2px 12px ${hexToRgba(rank.primary, 0.35)}` }}>
            {rank.icon} Ур. {profile.level} · {profile.levelName}
          </span>
        </div>
      </div>

      <div className="xp-card">
        <div className="xp-bar-labels">
          <span>{isMaxRank ? 'Максимальный ранг' : `${xpIntoLevel.toLocaleString()} / ${xpNeeded.toLocaleString()} XP`}</span>
          <span>{profile.xp.toLocaleString()} всего</span>
        </div>
        <div className="xp-bar">
          <div
            className="xp-fill"
            style={{
              width: `${xpProgress}%`,
              background: rank.rainbow
                ? 'linear-gradient(90deg, #FF0000, #FF7700, #FFFF00, #00FF00, #0099FF, #9933FF)'
                : `linear-gradient(90deg, ${rank.primary}, ${rank.secondary})`,
              boxShadow: `0 0 10px ${hexToRgba(rank.primary, 0.7)}`,
            }}
          />
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

      <div className="nav-links">
        <Link to="/wallet" className="nav-link-row">
          <span className="detail-label">💰 Кошелёк</span>
          <span className="nav-link-arrow">›</span>
        </Link>
        <Link to="/referrals" className="nav-link-row">
          <span className="detail-label">🤝 Рефералы</span>
          <span className="nav-link-arrow">›</span>
        </Link>
        <Link to="/polls" className="nav-link-row">
          <span className="detail-label">🗳 Голосования</span>
          <span className="nav-link-arrow">›</span>
        </Link>
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
