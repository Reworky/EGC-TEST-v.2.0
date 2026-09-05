import { useEffect, useState } from 'react';
import { getAdStatus, requestAdWatch, invalidateCache } from '../api/client';
import { useParticles } from './ParticlesContext';
import { useAdsgram } from '../hooks/useAdsgram';
import { useTelegaAds } from '../hooks/useTelegaAds';

const ADSGRAM_BLOCK_ID = import.meta.env.VITE_ADSGRAM_BLOCK_ID;
const TELEGA_AD_BLOCK_UUID = import.meta.env.VITE_TELEGA_AD_BLOCK_UUID;

/** Общая карточка одного рекламного блока — сеть/SDK передаётся снаружи через showAd (уже готовую
 * функцию показа из useAdsgram/useTelegaAds), сама карточка не знает, какая это сеть. */
function AdSlotView({ label, remaining, dailyCap, busy, message, onClick }) {
  const available = remaining !== null && remaining > 0;

  return (
    <div className="quest-card q-ads" style={{ marginBottom: 12 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <span className="quest-cat-badge ads">🎬 {label}</span>
      </div>

      <div className="quest-top">
        <div className="quest-title">Посмотри рекламу — получи EXC</div>
        <div className="quest-rewards">
          <span className="reward-exc"><i className="ti ti-coin"></i> +30 EXC</span>
        </div>
      </div>

      <div className="quest-meta" style={{ marginBottom: 12 }}>
        {remaining === null ? <span>Загрузка...</span> : <span>⏱ Осталось сегодня: {remaining}/{dailyCap}</span>}
      </div>

      {remaining !== null && (
        available ? (
          <button className="quest-btn" disabled={busy} onClick={onClick}>
            {busy ? 'Секунду...' : <><i className="ti ti-player-play" style={{ marginRight: 6 }} /> Смотреть</>}
          </button>
        ) : (
          <div className="quest-status quest-status-pending">
            <i className="ti ti-circle-check"></i> Лимит показов на сегодня исчерпан. Возвращайтесь завтра.
          </div>
        )
      )}
      {message && <div className="quest-message">{message}</div>}
    </div>
  );
}

/** Общий стейт "статус лимита для источника X" — у AdsGram и Telega.io разные дневные лимиты
 * (см. UserService.AdRewardSource на бэкенде), поэтому каждый слот следит за своим счётчиком отдельно,
 * а не делит один remaining на всех, как было раньше. */
function useAdSlotStatus(source) {
  const [remaining, setRemaining] = useState(null);
  const [dailyCap, setDailyCap] = useState(null);

  function reload() {
    getAdStatus(source).then(s => { setRemaining(s.remainingToday); setDailyCap(s.dailyCap); }).catch(() => {});
  }

  useEffect(() => { reload(); }, []);

  return { remaining, dailyCap, reload };
}

/** AdsGram-блок 45630. Второй блок (46037) пробовали параллельно для сравнения CPM — разница
 * оказалась статистическим шумом на маленькой выборке, выключили, оставили только этот. */
function AdsgramSlot({ blockId, label }) {
  const { remaining, dailyCap, reload } = useAdSlotStatus('ADSGRAM');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);
  const playParticles = useParticles();

  const showAd = useAdsgram({
    blockIds: [blockId],
    onReward: () => {
      setMessage('✅ Награда начислена!');
      playParticles?.('streakBonus', 3000);
      setTimeout(() => {
        invalidateCache('wallet');
        reload();
      }, 1500);
      setBusy(false);
    },
    onError: () => {
      setMessage('Не удалось показать рекламу, попробуйте ещё раз позже.');
      setBusy(false);
    },
  });

  async function handleClick() {
    setBusy(true);
    setMessage(null);
    try {
      await requestAdWatch('ADSGRAM');
      showAd();
    } catch {
      setMessage('Сейчас нет доступных показов, загляните позже.');
      setBusy(false);
    }
  }

  return <AdSlotView label={label} remaining={remaining} dailyCap={dailyCap} busy={busy} message={message} onClick={handleClick} />;
}

/** Рекламный блок Telega.io (отдельная сеть/SDK от AdsGram) — свой дневной лимит и та же
 * серверная защита через pendingAdRewardAt (см. PostbackController.telegaReward). */
function TelegaSlot({ adBlockUuid, label }) {
  const { remaining, dailyCap, reload } = useAdSlotStatus('TELEGA');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);
  const playParticles = useParticles();

  const showAd = useTelegaAds({
    adBlockUuid,
    onReward: () => {
      setMessage('✅ Награда начислена!');
      playParticles?.('streakBonus', 3000);
      setTimeout(() => {
        invalidateCache('wallet');
        reload();
      }, 1500);
      setBusy(false);
    },
    onError: () => {
      setMessage('Нет доступной рекламы, попробуйте ещё раз позже.');
      setBusy(false);
    },
  });

  async function handleClick() {
    setBusy(true);
    setMessage(null);
    try {
      await requestAdWatch('TELEGA');
      showAd();
    } catch {
      setMessage('Сейчас нет доступных показов, загляните позже.');
      setBusy(false);
    }
  }

  return <AdSlotView label={label} remaining={remaining} dailyCap={dailyCap} busy={busy} message={message} onClick={handleClick} />;
}

/** Два независимых рекламных блока (AdsGram 45630 + Telega.io), у каждого свой дневной лимит. */
export default function AdRewardCard() {
  return (
    <>
      <AdsgramSlot blockId={ADSGRAM_BLOCK_ID} label="Реклама 1" />
      {TELEGA_AD_BLOCK_UUID && (
        <TelegaSlot adBlockUuid={TELEGA_AD_BLOCK_UUID} label="Реклама 2" />
      )}
    </>
  );
}
