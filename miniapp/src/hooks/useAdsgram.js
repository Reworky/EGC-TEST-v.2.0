import { useCallback, useEffect, useRef } from 'react';

/** SDK AdsGram подключается в index.html (<script src="https://sad.adsgram.ai/js/sad.min.js">).
 * blockId берётся из VITE_ADSGRAM_BLOCK_ID — Reward-блок для платформы "app" в кабинете AdsGram. */
export function useAdsgram({ blockId, onReward, onError }) {
  const AdControllerRef = useRef(undefined);

  useEffect(() => {
    if (!blockId) return;
    AdControllerRef.current = window.Adsgram?.init({ blockId });
  }, [blockId]);

  return useCallback(() => {
    if (!AdControllerRef.current) {
      onError?.({ error: true, description: 'AdsGram недоступен' });
      return;
    }
    AdControllerRef.current.show()
      .then(() => { onReward(); })
      .catch((result) => { onError?.(result); });
  }, [onError, onReward]);
}
