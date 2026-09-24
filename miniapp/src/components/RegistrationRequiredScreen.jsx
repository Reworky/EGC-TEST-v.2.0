import { useEffect, useState } from 'react';

const BOT_USERNAME = import.meta.env.VITE_BOT_USERNAME || 'invitetogamebot';

/** Показывается вместо страниц мини-аппа, если игрок ещё не завершил регистрацию в боте (профиль отвечает 404:
 * никнейм не введён). Раньше такой игрок видел пустые страницы и ошибки на каждом экране и уходил. Кнопка ведёт
 * в бота БЕЗ start-параметра - иначе он мог бы затереть реферальную привязку. Когда игрок возвращается в мини-апп
 * (вкладка снова видима), проверка профиля запускается сама, ручная кнопка - на случай, если этого не произошло. */
export default function RegistrationRequiredScreen({ onRecheck }) {
  const [checking, setChecking] = useState(false);
  const [notYet, setNotYet] = useState(false);

  async function recheck() {
    setChecking(true);
    setNotYet(false);
    const done = await onRecheck();
    setChecking(false);
    if (!done) setNotYet(true);
  }

  useEffect(() => {
    function onVisible() {
      if (document.visibilityState === 'visible') onRecheck();
    }
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [onRecheck]);

  function openBot() {
    const url = `https://t.me/${BOT_USERNAME}`;
    const tg = window.Telegram?.WebApp;
    if (tg?.openTelegramLink) tg.openTelegramLink(url);
    else window.open(url, '_blank');
  }

  const primary = {
    marginTop: 8, padding: '14px 28px', borderRadius: 14, border: 'none', cursor: 'pointer',
    background: 'linear-gradient(135deg, #7c3aed, #a855f7 55%, #ec4899)', color: '#fff',
    fontSize: 16, fontWeight: 700, boxShadow: '0 0 24px rgba(168,85,247,0.35)', width: '100%', maxWidth: 320,
  };
  const secondary = {
    padding: '12px 24px', borderRadius: 12, cursor: 'pointer', width: '100%', maxWidth: 320,
    background: 'rgba(124,58,237,0.12)', border: '1px solid rgba(124,58,237,0.35)', color: '#a78bfa', fontSize: 14,
  };
  const step = { display: 'flex', gap: 10, alignItems: 'flex-start', textAlign: 'left', fontSize: 14, color: 'rgba(255,255,255,0.8)', lineHeight: 1.45 };
  const num = {
    flex: 'none', width: 22, height: 22, borderRadius: '50%', background: 'rgba(168,85,247,0.25)',
    color: '#d8b4fe', fontSize: 12, fontWeight: 700, display: 'flex', alignItems: 'center', justifyContent: 'center',
  };

  return (
    <div className="page-center" style={{ flexDirection: 'column', gap: 14, padding: '0 28px', textAlign: 'center' }}>
      <div style={{ fontSize: 52 }}>🎮</div>
      <div style={{ fontSize: 20, fontWeight: 700, color: 'var(--tg-theme-text-color, #e2e8f0)' }}>Осталось зарегистрироваться</div>
      <div style={{ fontSize: 14, color: 'rgba(255,255,255,0.55)', lineHeight: 1.5 }}>
        Квесты и награды откроются после короткой регистрации в боте. Это займёт минуту.
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10, width: '100%', maxWidth: 320, margin: '4px 0' }}>
        <div style={step}><span style={num}>1</span><span>Нажмите «Открыть бота» и запустите его (кнопка Start)</span></div>
        <div style={step}><span style={num}>2</span><span>Отправьте свой игровой никнейм, он должен совпадать с ником в игре</span></div>
        <div style={step}><span style={num}>3</span><span>Вернитесь сюда, и всё заработает</span></div>
      </div>
      <button style={primary} onClick={openBot}>Открыть бота</button>
      <button style={secondary} onClick={recheck} disabled={checking}>
        {checking ? 'Проверяю...' : 'Я уже зарегистрировался'}
      </button>
      {notYet && (
        <div style={{ fontSize: 13, color: '#fbbf24' }}>
          Регистрация пока не найдена. Завершите её в боте и нажмите ещё раз.
        </div>
      )}
    </div>
  );
}
