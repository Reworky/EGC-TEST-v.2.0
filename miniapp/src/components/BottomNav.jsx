import { NavLink } from 'react-router-dom';
import './BottomNav.css';

const tabs = [
  { to: '/profile', icon: '👤', label: 'Профиль' },
  { to: '/quests', icon: '🎯', label: 'Квесты' },
  { to: '/shop', icon: '🛒', label: 'Магазин' },
  { to: '/top', icon: '🏆', label: 'Топ' },
];

export default function BottomNav() {
  return (
    <nav className="bottom-nav">
      {tabs.map(t => (
        <NavLink key={t.to} to={t.to} className={({ isActive }) => isActive ? 'tab active' : 'tab'}>
          <span className="tab-icon">{t.icon}</span>
          <span className="tab-label">{t.label}</span>
        </NavLink>
      ))}
    </nav>
  );
}
