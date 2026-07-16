import { useEffect, useRef } from 'react';
import * as THREE from 'three';

export default function VantaBackground({ effect = 'NET', config = {}, style = {} }) {
  const containerRef = useRef(null);
  const vantaRef = useRef(null);

  useEffect(() => {
    let isMounted = true;

    async function init() {
      if (!containerRef.current || vantaRef.current) return;

      // Пропускаем Vanta на слабых устройствах
      if (navigator.hardwareConcurrency <= 2) return;

      const isLowEnd = navigator.hardwareConcurrency <= 4;

      try {
        let VantaEffect;
        if (effect === 'NET') {
          const mod = await import('vanta/dist/vanta.net.min');
          VantaEffect = mod.default;
        } else if (effect === 'WAVES') {
          const mod = await import('vanta/dist/vanta.waves.min');
          VantaEffect = mod.default;
        } else if (effect === 'DOTS') {
          const mod = await import('vanta/dist/vanta.dots.min');
          VantaEffect = mod.default;
        }

        if (!VantaEffect || !isMounted || !containerRef.current) return;

        const mergedConfig = { ...config };
        if (isLowEnd) {
          if (mergedConfig.points) mergedConfig.points = Math.min(mergedConfig.points, 6);
          if (mergedConfig.maxDistance) mergedConfig.maxDistance = Math.min(mergedConfig.maxDistance, 12);
          mergedConfig.mouseControls = false;
          mergedConfig.touchControls = false;
        }

        vantaRef.current = VantaEffect({
          el: containerRef.current,
          THREE,
          ...mergedConfig,
        });
      } catch (e) {
        // Vanta не загрузился — статичный фон через CSS достаточен
      }
    }

    init();

    return () => {
      isMounted = false;
      vantaRef.current?.destroy();
      vantaRef.current = null;
    };
  }, []);

  return (
    <div
      ref={containerRef}
      style={{
        position: 'fixed',
        top: 0, left: 0,
        width: '100%', height: '100%',
        zIndex: 0,
        background: 'linear-gradient(135deg, #0d0d1a 0%, #1a1a2e 100%)',
        ...style,
      }}
    />
  );
}
