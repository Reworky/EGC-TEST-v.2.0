import { useEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';
import { getProfile } from '../api/client';

const BLOCK_ID = import.meta.env.VITE_ADSGRAM_INTERSTITIAL_BLOCK_ID;
// Только «спокойные» экраны: не мешаем взятию квестов, выводу, колесу и поддержке.
const AD_PATHS = ['/top', '/shop', '/referrals', '/squads', '/polls'];
const MIN_GAP_MS = 30 * 60 * 1000;   // не чаще раза в 30 минут
const MAX_PER_DAY = 6;
const MIN_COMPLETED_QUESTS = 3;      // новичкам до третьего квеста рекламу не показываем: не рвём первые шаги

function read(key) {
  try { return localStorage.getItem(key); } catch (e) { return null; }
}
function write(key, value) {
  try { localStorage.setItem(key, value); } catch (e) { /* без хранилища просто работаем без лимита по времени */ }
}

/** AdsGram Interstitial: полноэкранный блок на переходе между экранами, без награды (CPM).
 *  Не показывается подписчикам EGC Pass и тем, у кого меньше трёх выполненных квестов. Любая ошибка рекламы молча игнорируется. */
export default function InterstitialAd() {
  const { pathname } = useLocation();
  const controllerRef = useRef(null);
  const busyRef = useRef(false);

  useEffect(() => {
    if (!BLOCK_ID || !AD_PATHS.includes(pathname) || busyRef.current) return undefined;
    const timer = setTimeout(async () => {
      try {
        const lastShown = Number(read('egc_interstitial_last') || 0);
        if (Date.now() - lastShown < MIN_GAP_MS) return;
        const today = new Date().toISOString().slice(0, 10);
        const [day, count] = (read('egc_interstitial_day') || '').split(':');
        if (day === today && Number(count) >= MAX_PER_DAY) return;
        const profile = await getProfile();
        if (!profile || profile.hasEgcPass || (profile.completedQuests ?? 0) < MIN_COMPLETED_QUESTS) return;
        if (!window.Adsgram) return;
        if (!controllerRef.current) controllerRef.current = window.Adsgram.init({ blockId: BLOCK_ID });
        busyRef.current = true;
        write('egc_interstitial_last', String(Date.now()));
        write('egc_interstitial_day', `${today}:${day === today ? Number(count) + 1 : 1}`);
        await controllerRef.current.show().catch(() => {});
      } catch (e) {
        // реклама не должна ломать приложение
      } finally {
        busyRef.current = false;
      }
    }, 1500);
    return () => clearTimeout(timer);
  }, [pathname]);

  return null;
}
