import { useRef } from 'react';

export default function SpotlightCard({ children, className = '', style = {}, onClick }) {
  const cardRef = useRef(null);

  function handleMove(clientX, clientY) {
    const card = cardRef.current;
    if (!card) return;
    const rect = card.getBoundingClientRect();
    const x = ((clientX - rect.left) / rect.width) * 100;
    const y = ((clientY - rect.top) / rect.height) * 100;
    card.style.setProperty('--mouse-x', x + '%');
    card.style.setProperty('--mouse-y', y + '%');
  }

  return (
    <div
      ref={cardRef}
      className={`spotlight-card ${className}`}
      style={style}
      onClick={onClick}
      onMouseMove={e => handleMove(e.clientX, e.clientY)}
      onTouchMove={e => {
        const t = e.touches[0];
        handleMove(t.clientX, t.clientY);
      }}
    >
      {children}
    </div>
  );
}
