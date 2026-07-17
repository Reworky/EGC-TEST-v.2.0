import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.jsx'

// Убрать белый/чёрный экран Telegram как можно раньше
window.Telegram?.WebApp?.ready();
window.Telegram?.WebApp?.expand();
window.Telegram?.WebApp?.setBackgroundColor('#0f1020');
window.Telegram?.WebApp?.setHeaderColor('#0f1020');

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
