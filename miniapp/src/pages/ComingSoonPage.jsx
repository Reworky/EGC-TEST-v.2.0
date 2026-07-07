import './ComingSoonPage.css';

export default function ComingSoonPage({ title = 'Скоро' }) {
  return (
    <div className="soon-page">
      <div className="soon-icon">🚀</div>
      <h2 className="soon-title">{title}</h2>
      <p className="soon-text">Этот раздел находится в разработке и скоро будет доступен.</p>
    </div>
  );
}
