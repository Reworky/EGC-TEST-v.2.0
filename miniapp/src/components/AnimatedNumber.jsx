import { useEffect, useRef } from 'react';
import gsap from 'gsap';

export default function AnimatedNumber({ value, duration = 1.5, flashColor = null, className = '', style = {} }) {
  const spanRef = useRef(null);
  const prevRef = useRef(value);
  const tweenRef = useRef(null);

  useEffect(() => {
    const from = prevRef.current;
    const to = value;
    prevRef.current = to;

    if (from === to || !spanRef.current) return;

    tweenRef.current?.kill();

    const obj = { v: from };

    if (flashColor) {
      gsap.to(spanRef.current, { color: flashColor, duration: 0.1, overwrite: true });
      gsap.to(spanRef.current, { color: 'inherit', duration: 0.8, delay: 0.3, overwrite: false });
    }

    tweenRef.current = gsap.to(obj, {
      v: to,
      duration,
      ease: 'power2.out',
      onUpdate() {
        if (spanRef.current) {
          spanRef.current.textContent = Math.round(obj.v).toLocaleString('ru-RU');
        }
      },
      onComplete() {
        if (spanRef.current) {
          spanRef.current.textContent = to.toLocaleString('ru-RU');
        }
      },
    });

    return () => tweenRef.current?.kill();
  }, [value]);

  return (
    <span ref={spanRef} className={className} style={style}>
      {value.toLocaleString('ru-RU')}
    </span>
  );
}
