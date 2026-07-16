import { useEffect, useRef, useState } from 'react';

// Заменить на свой URL после публикации сцены на spline.design
const SPLINE_URL = 'https://prod.spline.design/6Wq1Q7YGyM-iab9i/scene.splinecode';

export default function SplineHero({ coins }) {
  const containerRef = useRef(null);
  const viewerRef = useRef(null);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState(false);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const observer = new IntersectionObserver((entries) => {
      if (!entries[0].isIntersecting) return;
      observer.disconnect();

      // spline-viewer — web component из index.html script tag
      const viewer = document.createElement('spline-viewer');
      viewer.setAttribute('url', SPLINE_URL);
      viewer.setAttribute('loading-anim-type', 'spinner-small-dark');
      viewer.style.cssText = 'width:100%;height:100%;';
      viewerRef.current = viewer;

      viewer.addEventListener('load', () => setLoaded(true));
      viewer.addEventListener('error', () => setError(true));

      // Fallback если web component не определён через 4 сек
      const timer = setTimeout(() => {
        if (!loaded) setError(true);
      }, 4000);

      container.appendChild(viewer);
      return () => clearTimeout(timer);
    });

    observer.observe(container);
    return () => {
      observer.disconnect();
      viewerRef.current?.remove();
    };
  }, []);

  return (
    <div style={{ position: 'relative', width: '100%', height: 220, borderRadius: 16, overflow: 'hidden', marginBottom: 16 }}>
      {/* Fallback — показывается пока грузится Spline или при ошибке */}
      {!loaded && (
        <div style={{
          position: 'absolute', inset: 0,
          background: 'linear-gradient(135deg, #1a1535 0%, #0f1020 100%)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          {error
            ? <i className="ti ti-diamond" style={{ fontSize: 72, color: '#7B68EE', opacity: 0.7 }} />
            : <div style={{ width: 32, height: 32, borderRadius: '50%', border: '3px solid #7B68EE', borderTopColor: 'transparent', animation: 'spin 0.8s linear infinite' }} />
          }
        </div>
      )}

      {/* Контейнер для spline-viewer */}
      <div ref={containerRef} style={{ width: '100%', height: '100%' }} />

      {/* Оверлей с балансом */}
      <div style={{
        position: 'absolute', bottom: 0, left: 0, right: 0,
        padding: '12px 16px',
        background: 'linear-gradient(transparent, rgba(0,0,0,0.75))',
        borderRadius: '0 0 16px 16px',
      }}>
        <div style={{ fontSize: 12, color: 'rgba(255,255,255,0.6)' }}>Твой баланс</div>
        <div style={{ fontSize: 22, fontWeight: 600, color: '#F5A623' }}>
          <i className="ti ti-coin" style={{ marginRight: 4 }} />
          {coins?.toLocaleString() ?? '—'} EXC
        </div>
      </div>

      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}
