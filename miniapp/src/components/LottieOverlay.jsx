import { useEffect, useRef } from 'react';

export default function LottieOverlay({ playing, onDone }) {
  const containerRef = useRef(null);
  const animRef = useRef(null);

  useEffect(() => {
    if (!playing) return;
    let cancelled = false;
    // lottie-web (~250 КБ) — динамический импорт, а не статический сверху файла: этот компонент
    // смонтирован в корне приложения (LottieProvider), библиотека тянулась в главный бандл на КАЖДОЙ
    // странице, даже когда анимация никогда не проигрывается (2026-09-20, разбор размера бандлов).
    import('lottie-web').then(({ default: lottie }) => {
      if (cancelled || !containerRef.current) return;
      animRef.current = lottie.loadAnimation({
        container: containerRef.current,
        renderer: 'svg',
        loop: false,
        autoplay: true,
        path: '/static/animations/confetti.json',
      });
      animRef.current.addEventListener('complete', () => {
        onDone?.();
        animRef.current?.destroy();
        animRef.current = null;
      });
    });
    return () => {
      cancelled = true;
      animRef.current?.destroy();
      animRef.current = null;
    };
  }, [playing]);

  if (!playing) return null;

  return (
    <div style={{
      position: 'fixed', top: 0, left: 0,
      width: '100%', height: '100%',
      zIndex: 9999, pointerEvents: 'none',
    }}>
      <div ref={containerRef} style={{ width: '100%', height: '100%' }} />
    </div>
  );
}
