import { createContext, useContext, useState } from 'react';
import LottieOverlay from './LottieOverlay';

const LottieCtx = createContext(null);

export function LottieProvider({ children }) {
  const [playing, setPlaying] = useState(false);
  const play = () => setPlaying(true);
  return (
    <LottieCtx.Provider value={play}>
      {children}
      <LottieOverlay playing={playing} onDone={() => setPlaying(false)} />
    </LottieCtx.Provider>
  );
}

export const useLottie = () => useContext(LottieCtx);
