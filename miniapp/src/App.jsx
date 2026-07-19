import { lazy, Suspense, useEffect, useState } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { authMiniApp } from './api/client';
import { useTelegram } from './hooks/useTelegram';
import BottomNav from './components/BottomNav';
import { LottieProvider } from './components/LottieContext';
import { ParticlesProvider } from './components/ParticlesContext';
import './App.css';

const ProfilePage    = lazy(() => import('./pages/ProfilePage'));
const QuestsPage     = lazy(() => import('./pages/QuestsPage'));
const ShopPage       = lazy(() => import('./pages/ShopPage'));
const LeaderboardPage= lazy(() => import('./pages/LeaderboardPage'));
const ReferralsPage  = lazy(() => import('./pages/ReferralsPage'));
const WalletPage     = lazy(() => import('./pages/WalletPage'));
const PollsPage      = lazy(() => import('./pages/PollsPage'));
const SupportPage    = lazy(() => import('./pages/SupportPage'));
const BattlePassPage = lazy(() => import('./pages/BattlePassPage'));
const SquadsPage     = lazy(() => import('./pages/SquadsPage'));

export default function App() {
  const { initData } = useTelegram();
  const hasToken = !!localStorage.getItem('egc_token');
  const [ready, setReady] = useState(hasToken);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!initData) {
      setReady(true);
      return;
    }
    authMiniApp(initData)
      .then(() => setReady(true))
      .catch((e) => {
        if (!hasToken) {
          setError(`Ошибка: ${e?.response?.status || e?.message || 'нет ответа'} | URL: ${import.meta.env.VITE_API_URL || 'localhost:8090'}`);
        }
      });
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
        <Suspense fallback={null}>
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
          <Route path="/squads" element={<SquadsPage />} />
        </Routes>
        </Suspense>
        <BottomNav />
      </div>
      </ParticlesProvider>
      </LottieProvider>
    </BrowserRouter>
  );
}
