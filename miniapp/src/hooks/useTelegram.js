import { useEffect, useState } from 'react';

export function useTelegram() {
  const [tg, setTg] = useState(null);

  useEffect(() => {
    const app = window.Telegram?.WebApp;
    if (app) {
      app.ready();
      app.expand();
      setTg(app);
    }
  }, []);

  const user = tg?.initDataUnsafe?.user;
  const initData = tg?.initData ?? '';

  return { tg, user, initData };
}
