import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getReferrals } from '../api/client';
import './ReferralsPage.css';

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

  useEffect(() => {
    getReferrals().then(setData).catch(() => setError('Не удалось загрузить данные. Попробуйте ещё раз.'));
  }, []);

  function copyLink() {
    const markCopied = () => { setCopied(true); setTimeout(() => setCopied(false), 2000); };
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(data.referralLink).then(markCopied, () => fallbackCopy(data.referralLink, markCopied));
    } else {
      fallbackCopy(data.referralLink, markCopied);
    }
  }

  function shareLink() {
    const shareUrl = `https://t.me/share/url?url=${encodeURIComponent(data.referralLink)}&text=${encodeURIComponent('Присоединяйся к EXPERIENCE GAMING CLUB!')}`;
    const tg = window.Telegram?.WebApp;
    if (tg?.openTelegramLink) tg.openTelegramLink(shareUrl);
    else window.open(shareUrl, '_blank');
  }

  if (error) return <div style={{ padding: 32, color: '#ef4444', textAlign: 'center' }}>{error}</div>;
  if (!data) return <div style={{ padding: 32, color: '#888', textAlign: 'center' }}>Загрузка...</div>;

  const pct = Math.min(data.progressPercent, 100);

  return (
    <div className="ref-page">

      <div className="ref-hero">
        <div className="ref-hero-icon">🤝</div>
        <div className="ref-hero-title">Реферальная программа</div>
        <div className="ref-hero-sub">EXPERIENCE GAMING CLUB</div>
      </div>

      <div className="ref-stats-grid">
        <div className="ref-stat-card">
          <div className="ref-stat-accent" style={{ background: 'linear-gradient(90deg,#7c3aed,#a855f7)' }} />
          <div className="ref-stat-label">Приглашено</div>
          <div className="ref-stat-val">{data.invitedFriends}</div>
          <div className="ref-stat-unit">друзей</div>
        </div>
        <div className="ref-stat-card">
          <div className="ref-stat-accent" style={{ background: 'linear-gradient(90deg,#f59e0b,#fbbf24)' }} />
          <div className="ref-stat-label">Заработано</div>
          <div className="ref-stat-val">{data.earnedExc.toLocaleString()}</div>
          <div className="ref-stat-unit">EXC</div>
        </div>
      </div>

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
            <div className="ref-step-desc">Другу бонусом: <b>+3 000 EXC</b></div>
          </div>
        </div>

        <div className="ref-step">
          <div className="ref-step-num">3</div>
          <div className="ref-step-body">
            <div className="ref-step-title">Друг зарабатывает квестами</div>
            <div className="ref-step-desc">Ты получаешь <b>3% от каждого его EXC</b> в течение первых 14 дней автоматически</div>
          </div>
        </div>

        <div className="ref-how-footer">Скопируй ссылку и отправь другу — остальное система сделает сама.</div>
      </div>

    </div>
  );
}
