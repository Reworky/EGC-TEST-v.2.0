import { NavLink } from 'react-router-dom';
import './BottomNav.css';

const tabs = [
  { to: '/profile', icon: 'ti-user-circle', label: 'Профиль' },
  { to: '/quests', icon: 'ti-list-check', label: 'Квесты' },
  { to: '/shop', icon: 'ti-shopping-bag', label: 'Магазин' },
  { to: '/top', icon: 'ti-trophy', label: 'Топ' },
];

export default function BottomNav() {
  return (
    <nav className="bottom-nav">
      {tabs.map(t => (
        <NavLink key={t.to} to={t.to} className={({ isActive }) => isActive ? 'tab active' : 'tab'}>
          <i className={`ti ${t.icon} tab-icon`}></i>
          <span className="tab-label">{t.label}</span>
        </NavLink>
      ))}
    </nav>
  );
}
