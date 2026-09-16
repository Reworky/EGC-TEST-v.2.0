import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.jsx'

// Убрать белый/чёрный экран Telegram как можно раньше
window.Telegram?.WebApp?.ready();
window.Telegram?.WebApp?.expand();
window.Telegram?.WebApp?.setBackgroundColor('#0f1020');
window.Telegram?.WebApp?.setHeaderColor('#0f1020');
// Отключает случайное сворачивание/закрытие мини-аппа свайпом вниз по контенту (Bot API 7.7+) —
// свайп по шапке всё равно закрывает приложение, это Telegram не даёт отключить. На старых клиентах
// метод просто отсутствует в объекте WebApp — optional chaining делает вызов безопасным.
window.Telegram?.WebApp?.disableVerticalSwipes?.();
// Вторая линия защиты именно для свайпа по шапке, который нельзя отключить выше (Bot API 6.2+,
// поддержка шире, чем у disableVerticalSwipes) — перед любым закрытием (свайп по шапке, системная
// кнопка назад) Telegram сам покажет нативное подтверждение "Закрыть приложение?" вместо мгновенного
// закрытия — случайный свайп больше не потеряет прогресс молча.
window.Telegram?.WebApp?.enableClosingConfirmation?.();

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
