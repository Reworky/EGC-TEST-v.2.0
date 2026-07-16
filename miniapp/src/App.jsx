import { useEffect, useState } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { authMiniApp } from './api/client';
import { useTelegram } from './hooks/useTelegram';
import BottomNav from './components/BottomNav';
import { LottieProvider } from './components/LottieContext';
import { ParticlesProvider } from './components/ParticlesContext';
import ProfilePage from './pages/ProfilePage';
import QuestsPage from './pages/QuestsPage';
import ShopPage from './pages/ShopPage';
import LeaderboardPage from './pages/LeaderboardPage';
import ReferralsPage from './pages/ReferralsPage';
import WalletPage from './pages/WalletPage';
import PollsPage from './pages/PollsPage';
import SupportPage from './pages/SupportPage';
import BattlePassPage from './pages/BattlePassPage';
import './App.css';

export default function App() {
  const { initData } = useTelegram();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!initData) {
      // Dev mode — no Telegram context, skip auth
      setReady(true);
      return;
    }
    authMiniApp(initData)
      .then(() => setReady(true))
      .catch((e) => setError(`Ошибка: ${e?.response?.status || e?.message || 'нет ответа'} | URL: ${import.meta.env.VITE_API_URL || 'localhost:8090'}`));
  }, [initData]);

  if (error) {
    return (
      <div className="page-center error-msg">
        <div style={{ fontSize: 48 }}>⚠️</div>
        <div>{error}</div>
      </div>
    );
  }

  if (!ready) return <div className="page-center">Загрузка...</div>;

  return (
    <BrowserRouter>
      <LottieProvider>
      <ParticlesProvider>
      <div className="app">
        <Routes>
          <Route path="/" element={<Navigate to="/profile" replace />} />
          <Route path="/profile" element={<ProfilePage />} />
          <Route path="/quests" element={<QuestsPage />} />
          <Route path="/shop" element={<ShopPage />} />
          <Route path="/top" element={<LeaderboardPage />} />
          <Route path="/referrals" element={<ReferralsPage />} />
          <Route path="/wallet" element={<WalletPage />} />
          <Route path="/polls" element={<PollsPage />} />
          <Route path="/support" element={<SupportPage />} />
          <Route path="/battlepass" element={<BattlePassPage />} />
        </Routes>
        <BottomNav />
      </div>
      </ParticlesProvider>
      </LottieProvider>
    </BrowserRouter>
  );
}
