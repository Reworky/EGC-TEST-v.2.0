import { useEffect, useState } from 'react';
import { getFundRatioPercent } from '../api/client';

// Реальная стоимость звезды Telegram у реселлера в рублях (как STAR_RUB в HealthRatioService).
const STAR_RUB = 1.58;

/** «≈ 6,2 ⭐» - приблизительный эквивалент суммы EXC по текущему курсу фонда (только подпись, ничего не начисляет). */
export function starsApprox(exc, ratioPercent) {
  if (!exc || exc <= 0 || ratioPercent == null) return '';
  const stars = exc * (ratioPercent / 100) / 100 / STAR_RUB;
  if (stars < 0.1) return '≈ меньше 0,1 ⭐';
  if (stars < 10) return `≈ ${stars.toFixed(1).replace('.', ',')} ⭐`;
  return `≈ ${Math.round(stars).toLocaleString('ru-RU')} ⭐`;
}

/** Процент состояния фонда (null, пока не загрузился или при ошибке: тогда подписи просто не показываются). */
export function useFundRatio() {
  const [ratio, setRatio] = useState(null);
  useEffect(() => {
    let alive = true;
    getFundRatioPercent().then(r => { if (alive) setRatio(r); }).catch(() => {});
    return () => { alive = false; };
  }, []);
  return ratio;
}
