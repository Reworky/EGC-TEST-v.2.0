import { useEffect, useRef } from 'react';
import lottie from 'lottie-web';

export default function LottieOverlay({ playing, onDone }) {
  const containerRef = useRef(null);
  const animRef = useRef(null);

  useEffect(() => {
    if (!playing) return;
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
    return () => {
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
