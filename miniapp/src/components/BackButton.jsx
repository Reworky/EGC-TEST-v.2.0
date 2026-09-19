import { useNavigate } from 'react-router-dom';
import './BackButton.css';

// onClick — переопределяет переход по роуту произвольным действием (например, ShopPage использует
// эту же кнопку для возврата из открытого раздела к списку разделов внутри одной страницы,
// а не для навигации между страницами мини-аппа).
export default function BackButton({ to, label = 'Назад', onClick }) {
  const navigate = useNavigate();

  function handleClick() {
    if (onClick) onClick();
    else if (to) navigate(to);
    else navigate(-1);
  }

  return (
    <button className="back-btn" onClick={handleClick}>
      <span className="back-btn-arrow">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"
          strokeLinecap="round" strokeLinejoin="round">
          <path d="M19 12H5M12 5l-7 7 7 7" />
        </svg>
      </span>
      <span className="back-btn-label">{label}</span>
    </button>
  );
}
