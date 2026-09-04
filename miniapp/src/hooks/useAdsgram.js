import { useCallback, useEffect, useRef } from 'react';

/** SDK AdsGram подключается в index.html (<script src="https://sad.adsgram.ai/js/sad.min.js">).
 * blockIds — один или несколько Reward-блоков для платформы "app" в кабинете AdsGram, пробуются
 * по очереди: если первый не показал рекламу (ошибка/no-fill), пытаемся следующим — повышает
 * шанс реально показать рекламу игроку, а не просто отдаёт ошибку из-за одного пустого блока. */
export function useAdsgram({ blockIds, onReward, onError }) {
  const controllersRef = useRef([]);
  const ids = Array.isArray(blockIds) ? blockIds.filter(Boolean) : [blockIds].filter(Boolean);
  const idsKey = ids.join(',');

  useEffect(() => {
    controllersRef.current = ids.map((blockId) => window.Adsgram?.init({ blockId }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [idsKey]);

  return useCallback(() => {
    const controllers = controllersRef.current;
    if (!controllers.length || !controllers[0]) {
      onError?.({ error: true, description: 'AdsGram недоступен' });
      return;
    }
    const tryShow = (index) => {
      const controller = controllers[index];
      if (!controller) {
        onError?.({ error: true, description: 'Реклама сейчас недоступна' });
        return;
      }
      controller.show()
        .then(() => { onReward(); })
        .catch((result) => {
          if (index + 1 < controllers.length) {
            tryShow(index + 1);
          } else {
            onError?.(result);
          }
        });
    };
    tryShow(0);
  }, [onError, onReward]);
}
