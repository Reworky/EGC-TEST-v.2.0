import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { getProfile, getWallet, getBattlePass, getAvatarUrl } from '../api/client';
import AnimatedNumber from '../components/AnimatedNumber';
import fireFrame from '../assets/frames/fire.png';
import iceFrame from '../assets/frames/ice.png';
import purpleFrame from '../assets/frames/purple.png';
import goldFrame from '../assets/frames/gold.png';
import './ProfilePage.css';

const RANK_STYLES = {
  'Новичок':              { primary: '#9CA3AF', icon: '🥚' },
  'Игрок':               { primary: '#60A5FA', icon: '🎮' },
  'Ветеран':             { primary: '#34D399', icon: '⚔️' },
  'Элита':               { primary: '#A78BFA', icon: '💎' },
  'Легенда':             { primary: '#FB923C', icon: '⭐' },
  'Герой EXPERIENCE':    { primary: '#FBBF24', icon: '🌟' },
  'Чемпион EXPERIENCE':  { primary: '#818CF8', icon: '🏆' },
  'Амбассадор EXPERIENCE':{ primary: '#E2E8F0', icon: '🚀' },
};
const DEFAULT_RANK = { primary: '#9CA3AF', icon: '🥚' };

const TIER_THRESHOLDS = [0, 1000, 5000, 15000, 35000, 75000, 150000, 300000];
const FRAME_IMAGES = { fire: fireFrame, ice: iceFrame, purple: purpleFrame, gold: goldFrame };
const FRAME_SIZES = { fire: 116, ice: 152, purple: 124, gold: 130 };

// ── SVG illustrations ────────────────────────────────────────
const SVG_EXC = (
  <svg width="70" height="60" viewBox="0 0 70 60" fill="none">
    <circle cx="28" cy="36" r="18" fill="#92400e" opacity=".5"/>
    <circle cx="28" cy="36" r="13" fill="#b45309" opacity=".7"/>
    <circle cx="28" cy="36" r="9"  fill="#f59e0b"/>
    <text x="28" y="41" textAnchor="middle" fontSize="10" fontWeight="700" fill="#fef3c7">E</text>
    <circle cx="44" cy="44" r="12" fill="#78350f" opacity=".4"/>
    <circle cx="44" cy="44" r="8"  fill="#b45309" opacity=".6"/>
    <circle cx="44" cy="44" r="5"  fill="#f59e0b" opacity=".8"/>
    <circle cx="18" cy="42" r="9"  fill="#92400e" opacity=".35"/>
    <circle cx="18" cy="42" r="6"  fill="#d97706" opacity=".5"/>
  </svg>
);

const SVG_QUESTS = (
  <svg width="64" height="60" viewBox="0 0 64 60" fill="none">
    <circle cx="34" cy="34" r="20" fill="#1e1b4b" opacity=".8"/>
    <circle cx="34" cy="34" r="15" fill="none" stroke="#4338ca" strokeWidth="1.5" opacity=".6"/>
    <circle cx="34" cy="34" r="10" fill="none" stroke="#6366f1" strokeWidth="1.5" opacity=".7"/>
    <circle cx="34" cy="34" r="5"  fill="#818cf8" opacity=".9"/>
    <circle cx="34" cy="34" r="2.5" fill="#e0e7ff"/>
    <line x1="34" y1="14" x2="34" y2="34" stroke="#f87171" strokeWidth="2" strokeLinecap="round"/>
    <circle cx="34" cy="14" r="2" fill="#f87171"/>
    <line x1="34" y1="34" x2="48" y2="28" stroke="#fbbf24" strokeWidth="1.5" strokeLinecap="round" opacity=".7"/>
  </svg>
);

const SVG_STREAK = (
  <svg width="58" height="60" viewBox="0 0 58 60" fill="none">
    <ellipse cx="30" cy="50" rx="14" ry="5" fill="#7c2d12" opacity=".4"/>
    <path d="M30 48 C22 38 18 28 22 18 C24 12 28 8 30 6 C32 8 36 12 38 18 C42 28 38 38 30 48Z" fill="#c2410c" opacity=".6"/>
    <path d="M30 46 C24 37 21 29 24 20 C26 14 29 10 30 8 C31 10 34 14 36 20 C39 29 36 37 30 46Z" fill="#ea580c" opacity=".8"/>
    <path d="M30 42 C26 35 24 28 26 22 C27 18 29 15 30 13 C31 15 33 18 34 22 C36 28 34 35 30 42Z" fill="#f97316"/>
    <path d="M30 36 C27 31 26 26 28 22 C29 19 30 17 30 16 C30 17 31 19 32 22 C34 26 33 31 30 36Z" fill="#fbbf24"/>
    <circle cx="30" cy="26" r="4" fill="#fef3c7" opacity=".6"/>
  </svg>
);

const SVG_WALLET = (
  <svg width="60" height="58" viewBox="0 0 60 58" fill="none">
    <rect x="14" y="20" width="34" height="26" rx="5" fill="#14532d" opacity=".6"/>
    <rect x="14" y="20" width="34" height="26" rx="5" fill="none" stroke="#16a34a" strokeWidth="1" opacity=".5"/>
    <rect x="22" y="14" width="18" height="10" rx="3" fill="#166534" opacity=".7"/>
    <rect x="22" y="14" width="18" height="10" rx="3" fill="none" stroke="#22c55e" strokeWidth="1" opacity=".5"/>
    <rect x="18" y="28" width="26" height="12" rx="3" fill="#15803d" opacity=".5"/>
    <text x="31" y="38" textAnchor="middle" fontSize="8" fontWeight="700" fill="#4ade80" opacity=".9">EXC</text>
    <circle cx="31" cy="33" r="5" fill="#166534" opacity=".4"/>
    <path d="M29 33 L31 35 L34 30" stroke="#4ade80" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" opacity=".8"/>
  </svg>
);

// Menu SVGs
const SVG_MENU_BP = (
  <svg width="44" height="44" viewBox="0 0 44 44" fill="none">
    <rect x="8" y="14" width="28" height="18" rx="4" fill="#3b0764" opacity=".8"/>
    <rect x="8" y="14" width="28" height="18" rx="4" fill="none" stroke="#7c3aed" strokeWidth="1" opacity=".6"/>
    <rect x="14" y="10" width="16" height="8" rx="3" fill="#4c1d95" opacity=".7"/>
    <rect x="14" y="10" width="16" height="8" rx="3" fill="none" stroke="#8b5cf6" strokeWidth="1" opacity=".5"/>
    <circle cx="22" cy="23" r="4" fill="#6d28d9" opacity=".6"/>
    <circle cx="22" cy="23" r="2" fill="#a78bfa"/>
    <path d="M20 23 L22 25 L25 20" stroke="#e9d5ff" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round"/>
    <circle cx="32" cy="12" r="4" fill="#7c3aed"/>
    <text x="32" y="15" textAnchor="middle" fontSize="6" fontWeight="700" fill="#fff">BP</text>
  </svg>
);

const SVG_MENU_WALLET = (
  <svg width="44" height="44" viewBox="0 0 44 44" fill="none">
    <circle cx="22" cy="24" r="10" fill="#14532d" opacity=".6"/>
    <circle cx="22" cy="24" r="10" fill="none" stroke="#16a34a" strokeWidth="1" opacity=".5"/>
    <path d="M17 24 L20 27 L27 20" stroke="#4ade80" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
    <path d="M14 16 Q22 10 30 16" stroke="#22c55e" strokeWidth="1.5" fill="none" strokeLinecap="round" opacity=".5"/>
    <circle cx="14" cy="16" r="2" fill="#22c55e" opacity=".6"/>
    <circle cx="30" cy="16" r="2" fill="#22c55e" opacity=".6"/>
    <rect x="19" y="30" width="6" height="4" rx="1" fill="#166534" opacity=".6"/>
  </svg>
);

const SVG_MENU_REFS = (
  <svg width="44" height="44" viewBox="0 0 44 44" fill="none">
    <circle cx="22" cy="20" r="7" fill="#92400e" opacity=".5"/>
    <circle cx="22" cy="20" r="5" fill="#b45309" opacity=".7"/>
    <circle cx="22" cy="20" r="3" fill="#f59e0b"/>
    <circle cx="32" cy="26" r="5" fill="#78350f" opacity=".4"/>
    <circle cx="32" cy="26" r="3.5" fill="#b45309" opacity=".6"/>
    <circle cx="32" cy="26" r="2" fill="#fbbf24" opacity=".8"/>
    <circle cx="14" cy="27" r="4" fill="#92400e" opacity=".35"/>
    <circle cx="14" cy="27" r="2.5" fill="#d97706" opacity=".5"/>
    <path d="M18 32 Q22 28 26 32" stroke="#fbbf24" strokeWidth="1" fill="none" strokeLinecap="round" opacity=".4"/>
    <circle cx="12" cy="17" r="3" fill="#1d4ed8" opacity=".5"/>
    <path d="M10 16 L12 14 L14 16 L12 19Z" fill="#3b82f6" opacity=".7"/>
    <circle cx="32" cy="16" r="2.5" fill="#1d4ed8" opacity=".5"/>
    <path d="M30.5 14.5 L32 13 L33.5 14.5 L32 17Z" fill="#60a5fa" opacity=".7"/>
  </svg>
);

const SVG_MENU_POLLS = (
  <svg width="44" height="44" viewBox="0 0 44 44" fill="none">
    <circle cx="22" cy="22" r="10" fill="#1e1b4b" opacity=".7"/>
    <circle cx="22" cy="22" r="10" fill="none" stroke="#4338ca" strokeWidth="1" opacity=".5"/>
    <rect x="18" y="17" width="8" height="5" rx="1.5" fill="#4338ca" opacity=".8"/>
    <rect x="18" y="17" width="8" height="5" rx="1.5" fill="none" stroke="#818cf8" strokeWidth=".8"/>
    <path d="M18 22 L18 29 L22 27 L26 29 L26 22" fill="#3730a3" opacity=".6"/>
    <path d="M18 22 L18 29 L22 27 L26 29 L26 22" fill="none" stroke="#6366f1" strokeWidth=".8"/>
    <circle cx="32" cy="14" r="5" fill="#1e1b4b" opacity=".8"/>
    <circle cx="32" cy="14" r="5" fill="none" stroke="#6366f1" strokeWidth="1" opacity=".6"/>
    <path d="M30 14 L31.5 15.5 L34.5 11.5" stroke="#a5b4fc" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round"/>
  </svg>
);

const SVG_MENU_SUPPORT = (
  <svg width="44" height="44" viewBox="0 0 44 44" fill="none">
    <circle cx="22" cy="21" r="10" fill="#450a0a" opacity=".6"/>
    <circle cx="22" cy="21" r="10" fill="none" stroke="#991b1b" strokeWidth="1" opacity=".4"/>
    <path d="M17 19 Q17 15 22 15 Q27 15 27 19 Q27 22 24 23 L24 25 L20 25 L20 23 Q17 22 17 19Z" fill="#7f1d1d" opacity=".7"/>
    <path d="M17 19 Q17 15 22 15 Q27 15 27 19 Q27 22 24 23 L24 25 L20 25 L20 23 Q17 22 17 19Z" fill="none" stroke="#ef4444" strokeWidth="1"/>
    <rect x="20" y="26" width="4" height="3" rx="1" fill="#dc2626" opacity=".8"/>
    <circle cx="22" cy="19" r="2" fill="#fca5a5" opacity=".4"/>
    <circle cx="30" cy="14" r="4" fill="#450a0a" opacity=".5"/>
    <path d="M28 14 L30 12 L32 14 L30 17Z" fill="#f87171" opacity=".7"/>
  </svg>
);

// ── Streak dots ───────────────────────────────────────────────
function StreakDots({ days }) {
  return (
    <div className="p-dots">
      {Array.from({ length: 7 }, (_, i) => (
        <div key={i} className={`p-dot ${i < days ? 'on' : ''}`} />
      ))}
    </div>
  );
}

// ── XP bar with CSS-transition animation ─────────────────────
function XpBar({ xp, level, levelName }) {
  const tierIndex = level - 1;
  const tierStart = TIER_THRESHOLDS[tierIndex] ?? 0;
  const nextTierStart = TIER_THRESHOLDS[tierIndex + 1];
  const isMax = nextTierStart === undefined;
  const xpInto = xp - tierStart;
  const xpNeeded = isMax ? 1 : nextTierStart - tierStart;
  const targetPct = isMax ? 100 : Math.min(100, (xpInto / xpNeeded) * 100);

  const [width, setWidth] = useState(0);
  useEffect(() => {
    const t = setTimeout(() => setWidth(targetPct), 60);
    return () => clearTimeout(t);
  }, [targetPct]);

  return (
    <div className="p-xp-wrap">
      <div className="p-xp-header">
        <span className="p-xp-label">
          {isMax ? 'Максимальный ранг' : `${xpInto.toLocaleString('ru-RU')} / ${xpNeeded.toLocaleString('ru-RU')} XP`}
        </span>
        <span className="p-xp-total">{xp.toLocaleString('ru-RU')} всего</span>
      </div>
      <div className="p-xp-track">
        <div className="p-xp-fill" style={{ width: `${width}%` }} />
      </div>
      <div className="p-xp-sub">до след. уровня · {levelName}</div>
    </div>
  );
}

export default function ProfilePage() {
  const [profile, setProfile] = useState(null);
  const [wallet, setWallet] = useState(null);
  const [bp, setBp] = useState(null);
  const [avatarUrl, setAvatarUrl] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    Promise.all([
      getProfile(),
      getWallet().catch(() => null),
      getBattlePass().catch(() => null),
    ]).then(([p, w, b]) => {
      setProfile(p);
      setWallet(w);
      setBp(b);
    }).catch(() => setError('Профиль не найден. Зарегистрируйтесь через бота.'));
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

  const rank = RANK_STYLES[profile.levelName] || DEFAULT_RANK;
  const frameImage = FRAME_IMAGES[profile.avatarFrameImage];
  const frameSize = FRAME_SIZES[profile.avatarFrameImage] || 128;
  const ringColor = profile.avatarFrameColor || rank.primary;

  const coins = wallet?.coins ?? profile.coins ?? 0;
  const remaining = wallet?.remainingWithdrawalLimit ?? profile.remainingWithdrawalLimit ?? 0;
  const monthly = wallet?.monthlyWithdrawalLimit ?? 1;
  const withdrawPct = Math.min(100, (remaining / monthly) * 100);

  const hasActivePass = bp?.hasActivePass ?? false;

  return (
    <div className="p-page">

      {/* ── Hero ─────────────────────────────────────────────── */}
      <div className="p-hero">
        <div className="p-hero-bg" />
        <div className="p-hero-glow" />
        <div className="p-hero-glow2" />
        <div className="p-hero-content">
          {/* Avatar */}
          <div style={{ position: 'relative', display: 'inline-block' }}>
            <div className="p-avatar" style={{ borderColor: ringColor, color: ringColor, background: avatarUrl ? 'transparent' : `${ringColor}22` }}>
              {avatarUrl
                ? <img src={avatarUrl} alt="" style={{ width: '100%', height: '100%', borderRadius: '50%', objectFit: 'cover' }} />
                : (profile.nickname?.[0]?.toUpperCase() || '?')}
            </div>
            <div className="p-avatar-ring" style={{ borderColor: `${ringColor}44` }} />
            {frameImage && (
              <img src={frameImage} alt="" style={{ position: 'absolute', top: '50%', left: '50%', transform: 'translate(-50%,-50%)', width: frameSize, height: frameSize, pointerEvents: 'none' }} />
            )}
          </div>

          <div style={{ marginTop: 20 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <span className="p-nickname">{profile.nickname}</span>
              {profile.isCouncilMember && <i className="ti ti-crown" style={{ color: '#FFD700', fontSize: 14 }} />}
            </div>
            <div style={{ marginTop: 35 }}>
              <div className="p-rank-badge" style={{ display: 'inline-flex', borderColor: `${rank.primary}55`, color: rank.primary, background: `${rank.primary}18` }}>
                <span>{rank.icon}</span>
                <span>Ур. {profile.level} · {profile.levelName}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* ── XP Bar ───────────────────────────────────────────── */}
      <XpBar xp={profile.xp} level={profile.level} levelName={profile.levelName} />

      {/* ── Stats cards 2×2 ─────────────────────────────────── */}
      <div className="p-cards-grid">

        {/* EXC */}
        <div className="p-stat-card" style={{ background: 'linear-gradient(135deg,#1a1200,#0f0a00)', borderColor: 'rgba(245,158,11,0.15)' }}>
          <div className="p-card-accent" style={{ background: '#f59e0b' }} />
          <i className="ti ti-chevron-right p-card-arr" />
          <div className="p-card-label">EXC баланс</div>
          <div className="p-card-val"><AnimatedNumber value={coins} flashColor="#F5A623" /></div>
          <div className="p-card-sub" style={{ color: 'rgba(245,158,11,0.5)' }}>монет клуба</div>
          <div className="p-card-illus">{SVG_EXC}</div>
        </div>

        {/* Quests */}
        <div className="p-stat-card" style={{ background: 'linear-gradient(135deg,#0a0a1e,#080812)', borderColor: 'rgba(99,102,241,0.15)' }}>
          <div className="p-card-accent" style={{ background: '#6366f1' }} />
          <i className="ti ti-chevron-right p-card-arr" />
          <div className="p-card-label">Квесты</div>
          <div className="p-card-val"><AnimatedNumber value={profile.completedQuests} /></div>
          <div className="p-card-sub" style={{ color: 'rgba(99,102,241,0.6)' }}>выполнено</div>
          <div className="p-card-illus">{SVG_QUESTS}</div>
        </div>

        {/* Streak */}
        <div className="p-stat-card" style={{ background: 'linear-gradient(135deg,#1a0a00,#0f0600)', borderColor: 'rgba(249,115,22,0.15)' }}>
          <div className="p-card-accent" style={{ background: '#f97316' }} />
          <i className="ti ti-chevron-right p-card-arr" />
          <div className="p-card-label">Серия дней</div>
          <div className="p-card-val">{profile.streakDays}</div>
          <StreakDots days={profile.streakDays} />
          <div className="p-card-illus">{SVG_STREAK}</div>
        </div>

        {/* Withdrawal */}
        <div className="p-stat-card" style={{ background: 'linear-gradient(135deg,#001a0a,#000f06)', borderColor: 'rgba(34,197,94,0.15)' }}>
          <div className="p-card-accent" style={{ background: '#22c55e' }} />
          <i className="ti ti-chevron-right p-card-arr" />
          <div className="p-card-label">Лимит вывода</div>
          <div className="p-card-val" style={{ fontSize: 16 }}>{remaining.toLocaleString('ru-RU')}</div>
          <div className="p-card-sub" style={{ color: 'rgba(34,197,94,0.5)', fontSize: 10 }}>из {monthly.toLocaleString('ru-RU')} EXC</div>
          <div className="p-withdraw-bar">
            <div className="p-withdraw-fill" style={{ width: `${withdrawPct}%` }} />
          </div>
          <div className="p-card-illus">{SVG_WALLET}</div>
        </div>
      </div>

      {/* ── Menu ─────────────────────────────────────────────── */}
      <div className="p-menu">

        {/* Battle Pass */}
        <Link to="/battlepass" className="p-menu-item" style={{ borderColor: 'rgba(139,92,246,0.2)' }}>
          <div className="p-menu-illus" style={{ background: 'linear-gradient(135deg,#1a0d2e,#0f0720)' }}>
            {SVG_MENU_BP}
          </div>
          <div className="p-menu-texts">
            <div className="p-menu-title">Battle Pass</div>
            <div className="p-menu-sub">{hasActivePass ? 'Сезонный пропуск активен' : 'Получи эксклюзивные награды'}</div>
          </div>
          {hasActivePass
            ? <span className="p-badge-active">Активен</span>
            : <span className="p-badge-buy">3 000 EXC</span>}
        </Link>

        {/* Wallet */}
        <Link to="/wallet" className="p-menu-item">
          <div className="p-menu-illus" style={{ background: 'linear-gradient(135deg,#001a0d,#000f07)' }}>
            {SVG_MENU_WALLET}
          </div>
          <div className="p-menu-texts">
            <div className="p-menu-title">Кошелёк</div>
            <div className="p-menu-sub">Вывод EXC · история заявок</div>
          </div>
          <i className="ti ti-chevron-right" style={{ color: 'rgba(255,255,255,0.25)', marginRight: 12 }} />
        </Link>

        {/* Referrals */}
        <Link to="/referrals" className="p-menu-item">
          <div className="p-menu-illus" style={{ background: 'linear-gradient(135deg,#1a1200,#0f0a00)' }}>
            {SVG_MENU_REFS}
          </div>
          <div className="p-menu-texts">
            <div className="p-menu-title">Рефералы</div>
            <div className="p-menu-sub">Пригласи друга — получи бонус</div>
          </div>
          <i className="ti ti-chevron-right" style={{ color: 'rgba(255,255,255,0.25)', marginRight: 12 }} />
        </Link>

        {/* Polls */}
        <Link to="/polls" className="p-menu-item">
          <div className="p-menu-illus" style={{ background: 'linear-gradient(135deg,#0a0a1e,#060612)' }}>
            {SVG_MENU_POLLS}
          </div>
          <div className="p-menu-texts">
            <div className="p-menu-title">Голосования</div>
            <div className="p-menu-sub">Влияй на жизнь клуба</div>
          </div>
          <i className="ti ti-chevron-right" style={{ color: 'rgba(255,255,255,0.25)', marginRight: 12 }} />
        </Link>

        {/* Support */}
        <Link to="/support" className="p-menu-item">
          <div className="p-menu-illus" style={{ background: 'linear-gradient(135deg,#1a0a0a,#0f0606)' }}>
            {SVG_MENU_SUPPORT}
          </div>
          <div className="p-menu-texts">
            <div className="p-menu-title">Поддержка</div>
            <div className="p-menu-sub">Вопросы и обращения</div>
          </div>
          <i className="ti ti-chevron-right" style={{ color: 'rgba(255,255,255,0.25)', marginRight: 12 }} />
        </Link>

      </div>

      {/* Доп. информация о профиле */}
      {(profile.country || profile.platformsCsv) && (
        <div className="p-extra">
          {profile.country && <div className="p-extra-row"><span>🌍 Страна</span><span>{profile.country}</span></div>}
          {profile.platformsCsv && <div className="p-extra-row"><span>🎮 Платформы</span><span>{profile.platformsCsv}</span></div>}
        </div>
      )}

    </div>
  );
}
