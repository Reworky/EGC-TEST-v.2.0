import { useNavigate } from 'react-router-dom';
import './BackButton.css';

export default function BackButton({ to, label = 'Назад' }) {
  const navigate = useNavigate();

  function handleClick() {
    if (to) navigate(to);
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
