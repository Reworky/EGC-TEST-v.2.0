import { useNavigate } from 'react-router-dom';

/** Кнопка «Оформить EGC Pass» под сообщением об упоре в лимит (сообщения сервера для подписчика этих слов не содержат). */
export default function PassUpsell({ message, hide = false }) {
  const navigate = useNavigate();
  if (hide || !message || !message.includes('EGC Pass')) return null;
  return (
    <button className="quest-btn" style={{ marginTop: 8 }} onClick={() => navigate('/egcpass')}>
      ⭐ Оформить EGC Pass
    </button>
  );
}
