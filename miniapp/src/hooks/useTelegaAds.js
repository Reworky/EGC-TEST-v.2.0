import { useCallback } from 'react';

/** SDK Telega.io подключается в index.html (create_miniapp сохраняет объект в window.__telegaAds).
 * ad_show возвращает Promise — резолвится при показе, реджектится при ошибке (сама награда
 * начисляется server-to-server через их Reward URL, см. PostbackController.telegaReward). */
export function useTelegaAds({ adBlockUuid, onReward, onError }) {
  return useCallback(() => {
    const ads = window.__telegaAds;
    if (!adBlockUuid || !ads || typeof ads.ad_show !== 'function') {
      onError?.({ description: 'Telega.io ads недоступен' });
      return;
    }
    Promise.resolve(ads.ad_show({ adBlockUuid }))
      .then(() => { onReward(); })
      .catch((result) => { onError?.(result); });
  }, [adBlockUuid, onError, onReward]);
}
