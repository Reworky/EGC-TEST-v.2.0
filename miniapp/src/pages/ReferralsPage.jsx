import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getReferrals } from '../api/client';
import './QuestsPage.css';
import './ProfilePage.css';
import './ReferralsPage.css';

function fallbackCopy(text, onDone) {
  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.style.position = 'fixed';
  textarea.style.opacity = '0';
  document.body.appendChild(textarea);
  textarea.focus();
  textarea.select();
  try {
    document.execCommand('copy');
    onDone();
  } catch {
    // Копирование недоступно в этом окружении — пользователь может выделить ссылку вручную.
  }
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
    const markCopied = () => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    };
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(data.referralLink).then(markCopied, () => fallbackCopy(data.referralLink, markCopied));
    } else {
      fallbackCopy(data.referralLink, markCopied);
    }
  }

  function shareLink() {
    const shareUrl = `https://t.me/share/url?url=${encodeURIComponent(data.referralLink)}&text=${encodeURIComponent('Присоединяйся к EXPERIENCE GAMING CLUB!')}`;
    const tg = window.Telegram?.WebApp;
    if (tg?.openTelegramLink) {
      tg.openTelegramLink(shareUrl);
    } else {
      window.open(shareUrl, '_blank');
    }
  }

  if (error) return <div className="page-center error-msg">{error}</div>;
  if (!data) return <div className="page-center">Загрузка...</div>;

  const filled = Math.round(data.progressPercent / 10);
  const bar = '█'.repeat(filled) + '░'.repeat(10 - filled);

  return (
    <div className="ref-page">
      <Link to="/profile" className="ref-back">← Профиль</Link>

      <h2 className="ref-title">🤝 Реферальная программа EGC</h2>

      <div className="ref-link-card">
        <div className="ref-link-label">Ваша ссылка</div>
        <div className="ref-link-value">{data.referralLink}</div>
        <div className="ref-link-actions">
          <button className="quest-btn" onClick={copyLink}>{copied ? '✅ Скопировано' : '📋 Копировать'}</button>
          <button className="quest-btn quest-btn-secondary" onClick={shareLink}>📤 Поделиться</button>
        </div>
      </div>

      <div className="ref-stats-grid">
        <div className="stat-card">
          <div className="stat-icon">👥</div>
          <div className="stat-value">{data.invitedFriends}</div>
          <div className="stat-label">Друзей</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon">💎</div>
          <div className="stat-value">{data.earnedExc.toLocaleString()}</div>
          <div className="stat-label">EXC заработано</div>
        </div>
      </div>

      <div className="ref-progress-card">
        <div className="ref-progress-label">Прогресс до {data.nextMilestone.toLocaleString()} EXC</div>
        <div className="ref-progress-bar">{bar} {data.progressPercent}%</div>
      </div>

      <div className="ref-how-card">
        <div className="ref-how-title">🎁 Как работает</div>

        <div className="ref-step">
          <div className="ref-step-title">Шаг 1 — друг вступает в клуб</div>
          <div className="ref-step-desc">Тебе сразу: <b>+300 EXC</b> · Другу сразу: <b>+500 EXC</b></div>
        </div>

        <div className="ref-step">
          <div className="ref-step-title">Шаг 2 — друг выполняет первый квест</div>
          <div className="ref-step-desc">Другу бонусом: <b>+3 000 EXC</b></div>
        </div>

        <div className="ref-step">
          <div className="ref-step-title">Шаг 3 — друг зарабатывает квестами</div>
          <div className="ref-step-desc">Ты получаешь <b>3% от каждого его EXC</b> в течение первых 14 дней автоматически</div>
        </div>

        <div className="ref-how-footer">Скопируй ссылку и отправь другу — остальное система сделает сама.</div>
      </div>
    </div>
  );
}
