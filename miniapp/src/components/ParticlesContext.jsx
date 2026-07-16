import { createContext, useCallback, useContext, useRef } from 'react';

const ParticlesCtx = createContext(null);

const PRESETS = {
  questApproved: {
    particles: {
      number: { value: 0 },
      color: { value: ['#FFD700', '#FFA500', '#7B68EE', '#00CED1'] },
      shape: { type: ['circle', 'square'] },
      opacity: { value: { min: 0.6, max: 1 } },
      size: { value: { min: 4, max: 10 } },
      move: {
        enable: true, speed: 10, direction: 'top',
        random: true, outModes: { default: 'destroy' },
        gravity: { enable: true, acceleration: 12 },
      },
      life: { duration: { sync: true, value: 3 }, count: 1 },
      rotate: { value: { min: 0, max: 360 }, direction: 'random', animation: { enable: true, speed: 30 } },
    },
    emitters: { position: { x: 50, y: 110 }, rate: { delay: 0, quantity: 80 }, life: { count: 1, duration: 0.15 } },
  },
  levelUp: {
    particles: {
      number: { value: 0 },
      color: { value: ['#7B68EE', '#9370DB', '#FFD700', '#ffffff'] },
      shape: { type: 'star' },
      opacity: { value: 1 },
      size: { value: { min: 5, max: 14 } },
      move: {
        enable: true, speed: 7, direction: 'none',
        random: true, outModes: { default: 'destroy' },
        gravity: { enable: true, acceleration: 5 },
      },
      life: { duration: { sync: true, value: 4 }, count: 1 },
    },
    emitters: { position: { x: 50, y: 60 }, rate: { delay: 0, quantity: 60 }, life: { count: 1, duration: 0.2 } },
  },
  streakBonus: {
    particles: {
      number: { value: 0 },
      color: { value: ['#FF4500', '#FF6347', '#FFA500', '#FFD700'] },
      shape: { type: 'circle' },
      opacity: { value: { min: 0.6, max: 1 } },
      size: { value: { min: 3, max: 8 } },
      move: {
        enable: true, speed: 6, direction: 'top',
        random: true, outModes: { default: 'destroy' },
        gravity: { enable: false },
      },
      life: { duration: { sync: true, value: 2.5 }, count: 1 },
    },
    emitters: { position: { x: 50, y: 95 }, rate: { delay: 0, quantity: 50 }, life: { count: 1, duration: 0.2 } },
  },
};

let engineReady = false;
async function ensureEngine() {
  if (engineReady) return;
  const [{ tsParticles }, { loadSlim }] = await Promise.all([
    import('@tsparticles/engine'),
    import('@tsparticles/slim'),
  ]);
  await loadSlim(tsParticles);
  engineReady = true;
}

export function ParticlesProvider({ children }) {
  const containerRef = useRef(null);
  const activeRef = useRef(null);
  const overlayRef = useRef(null);

  const play = useCallback(async (preset = 'questApproved', duration = 3500) => {
    if (!overlayRef.current) return;
    try {
      await ensureEngine();
      const { tsParticles } = await import('@tsparticles/engine');

      // Уничтожить предыдущий если ещё идёт
      if (activeRef.current) {
        await activeRef.current.destroy();
        activeRef.current = null;
      }

      overlayRef.current.style.display = 'block';

      const config = PRESETS[preset] || PRESETS.questApproved;
      const container = await tsParticles.load({ id: 'egc-particles', options: config });
      activeRef.current = container;

      setTimeout(async () => {
        if (activeRef.current) {
          await activeRef.current.destroy();
          activeRef.current = null;
        }
        if (overlayRef.current) overlayRef.current.style.display = 'none';
      }, duration);
    } catch (e) {
      // Частицы не критичны — молча пропускаем
    }
  }, []);

  return (
    <ParticlesCtx.Provider value={play}>
      {children}
      <div
        ref={overlayRef}
        style={{ display: 'none', position: 'fixed', inset: 0, zIndex: 9998, pointerEvents: 'none' }}
      >
        <div id="egc-particles" ref={containerRef} style={{ width: '100%', height: '100%' }} />
      </div>
    </ParticlesCtx.Provider>
  );
}

export const useParticles = () => useContext(ParticlesCtx);
