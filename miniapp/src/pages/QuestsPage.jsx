import { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { getQuests, getSponsoredQuests, getSponsoredOverview, channelCheckQuest, getGames, getGamePhotoUrl, getQuestBoost, getQuestDetail, takeQuest, submitQuestReport, getMyQuests, getQuestSlots, cancelMyQuest, getTournament, joinTournament, getTournamentLeaderboard, getTournamentPhotoUrl } from '../api/client';
import { useLottie } from '../components/LottieContext';
import { useParticles } from '../components/ParticlesContext';
import AdRewardCard from '../components/AdRewardCard';
import { starsApprox, useFundRatio } from '../utils/fundStars';
import PassUpsell from '../components/PassUpsell';
import './QuestsPage.css';

const CATEGORY_ORDER = ['Лёгкие', 'Средние', 'Сложные'];
const CATEGORY_COLORS = { 'Лёгкие': '#66bb6a', 'Средние': '#ffa726', 'Сложные': '#ef5350' };
const CATEGORY_BADGE = { 'Лёгкие': 'easy', 'Средние': 'medium', 'Сложные': 'hard' };
const CATEGORY_CLASS = { 'Лёгкие': 'q-easy', 'Средние': 'q-medium', 'Сложные': 'q-hard' };
const CATEGORY_TICKETS = { 'Лёгкие': 1, 'Средние': 2, 'Сложные': 3 };
const QUEST_SECTIONS = [
  { key: 'gaming', label: '🎮 Игровые' },
  { key: 'sponsored', label: '💼 Спонсоры' },
  { key: 'ugc', label: '📹 UGC' },
  { key: 'ads', label: '🎬 Реклама' },
];

function pluralQuests(n) {
  if (n % 100 >= 11 && n % 100 <= 14) return 'квестов';
  const last = n % 10;
  if (last === 1) return 'квест';
  if (last >= 2 && last <= 4) return 'квеста';
  return 'квестов';
}

// Правило недельного лимита (3.4): почему награда EXC на карточке вдвое меньше номинала.
// q — элемент списка (есть weeklyLimit), d — детальная карточка (есть ещё weeklyCompleted, грузится позже).
function weeklyLimitText(q, d) {
  const limit = d?.weeklyLimit ?? q.weeklyLimit;
  const done = d?.weeklyCompleted;
  const already = done != null
    ? `${done} ${pluralQuests(done)}`
    : `${limit}+ ${pluralQuests(limit)}`;
  return `⚠️ Награда EXC снижена вдвое: за последние 7 дней вы уже выполнили ${already} такого типа (полная награда — за первые ${limit}). Вернётся к полной, когда самые старые из них выйдут из 7-дневного окна.`;
}

function openUrl(url) {
  const tg = window.Telegram?.WebApp;
  if (tg) {
    url.includes('t.me/') ? tg.openTelegramLink(url) : tg.openLink(url);
  } else {
    window.open(url, '_blank', 'noopener');
  }
}

// Блоки текста через пустую строку (как splitQuestBlocks в боте): если текст начинается с номера шага (1️⃣), блок начинается только с номера.
function splitQuestBlocks(text) {
  if (!text || !text.trim()) return [];
  const numbered = /^\d\uFE0F?\u20E3/.test(text.trim());
  const parts = text.split(numbered ? /\n\s*\n(?=\d\uFE0F?\u20E3)/ : /\n\s*\n/);
  return parts.map(b => b.trim()).filter(Boolean).map(b => {
    const i = b.indexOf('\n');
    return i < 0 ? { head: b, body: '' } : { head: b.slice(0, i), body: b.slice(i + 1) };
  });
}

// Раскрывающаяся цитата, как в Telegram: свёрнута до первых строк, нажатие раскрывает.
function ExpandableQuote({ title, children }) {
  const [open, setOpen] = useState(false);
  return (
    <div className={`sp-quote ${open ? 'open' : ''}`} onClick={e => { e.stopPropagation(); setOpen(o => !o); }}>
      <div className="sp-quote-title">{title}</div>
      <div className="sp-quote-body">{children}</div>
    </div>
  );
}

const SPONSOR_HEADINGS = ['Что ты получаешь:', 'Награда за квест:', 'Как это работает:'];

// Описание спонсорского квеста: блоки «Что ты получаешь» / «Награда за квест» / «Как это работает» - раскрывающиеся цитаты (как в карточке бота).
// У партнёрского квеста с шагами (miss) пустой хвостовой заголовок «Как это работает:» убираем: вместо него цитата с заголовками шагов (extra).
function SponsorDescription({ text, mission, instruction, requirements }) {
  const lines = (text || '').split('\n');
  const nodes = [];
  let plain = [];
  const flush = () => { if (plain.join('').trim()) nodes.push({ t: 'p', text: plain.join('\n').trim() }); plain = []; };
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (SPONSOR_HEADINGS.includes(line)) {
      flush();
      const body = [];
      let j = i + 1;
      while (j < lines.length && lines[j].trim() !== '') { body.push(lines[j]); j++; }
      if (!(mission && body.length === 0 && line === 'Как это работает:')) nodes.push({ t: 'q', title: line, text: body.join('\n') });
      i = j - 1;
    } else {
      plain.push(lines[i]);
    }
  }
  flush();
  const steps = mission ? splitQuestBlocks(instruction) : [];
  const conds = mission ? splitQuestBlocks(requirements) : [];
  return (
    <div className="sp-desc">
      {nodes.map((n, i) => n.t === 'p'
        ? <p key={i} className="quest-desc" style={{ whiteSpace: 'pre-line' }}>{renderTextWithLinks(n.text)}</p>
        : n.text
          ? <ExpandableQuote key={i} title={n.title}>{renderTextWithLinks(n.text)}</ExpandableQuote>
          : null)}
      {steps.length > 0 && (
        <ExpandableQuote title="Как это работает:">{steps.map(b => b.head).join('\n')}</ExpandableQuote>
      )}
      {conds.map((c, i) => (
        <ExpandableQuote key={'c' + i} title={c.head}>{renderTextWithLinks(c.body)}</ExpandableQuote>
      ))}
    </div>
  );
}

// Баннеры UGC-квестов по площадке (quest.platform): youtube есть, tiktok и instagram появятся с одобрением картинок (onError прячет отсутствующий файл)
const UGC_BANNERS = { youtube: '/ugc/youtube.jpg', tiktok: '/ugc/tiktok.jpg', instagram: '/ugc/instagram.jpg' };
const PARTNER_BANNERS = { otp: '/sponsored/otp.jpg', vtb: '/sponsored/vtb.jpg', alfa: '/sponsored/alfa.jpg', fora: '/sponsored/fora.jpg', psb: '/sponsored/psb.jpg', ubrr: '/sponsored/ubrr.jpg', tblack: '/sponsored/tblack.jpg', tmobile: '/sponsored/tmobile.jpg' };

function LinkPill({ url }) {
  const [copied, setCopied] = useState(false);

  function handleOpen(e) {
    e.stopPropagation();
    const tg = window.Telegram?.WebApp;
    if (tg) {
      url.includes('t.me/') ? tg.openTelegramLink(url) : tg.openLink(url);
    } else {
      window.open(url, '_blank', 'noopener');
    }
  }

  function handleCopy(e) {
    e.stopPropagation();
    navigator.clipboard.writeText(url).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  }

  const display = url.replace(/^https?:\/\//, '').replace(/\/$/, '');

  return (
    <span className="quest-link-pill" onClick={e => e.stopPropagation()}>
      <span className="quest-link-url" onClick={handleOpen}>🔗 {display}</span>
      <button className="quest-link-copy" onClick={handleCopy} title="Копировать ссылку">
        {copied ? '✓' : '⎘'}
      </button>
    </span>
  );
}

function renderTextWithLinks(text) {
  if (!text) return null;
  const parts = text.split(/(https?:\/\/[^\s<>"']+)/);
  return parts.map((part, i) =>
    /^https?:\/\//.test(part) ? <LinkPill key={i} url={part} /> : part
  );
}

/** Подсказка после взятия квеста: человек должен сразу пойти в игру (в карточке она держится, пока квест в работе). */
function GoPlayHint({ gameName, auto }) {
  return (
    <div className="quest-go-hint">
      <div className="quest-go-hint-title">🎮 Теперь заходите в игру{gameName ? ` «${gameName}»` : ''} и выполняйте задание</div>
      <div className="quest-go-hint-text">
        {auto ? 'Прогресс засчитается сам, вернётесь сюда за наградой.' : 'Когда закончите, возвращайтесь сюда и отправьте отчёт.'}
      </div>
    </div>
  );
}

/** Убирает из инструкции квеста с автопроверкой фразы про автоматический учёт: вместо них одна общая строка (упрощение карточки, 05.10.2026). */
function simplifyAutoInstruction(text) {
  if (!text) return '';
  // Без lookbehind в регулярке: на iOS старше 16.4 он ломает разбор всего бандла
  return (text.trim().match(/[^.!?]+[.!?]*\s*/g) || []).map(x => x.trim()).filter(Boolean).filter(sentence => {
    const low = sentence.toLowerCase();
    const autoPhrase = (low.includes('автоматическ') && (low.includes('прогресс') || low.includes('проверя')))
      || low.includes('ничего сообщать') || low.includes('ничего отправлять') || low.includes('отчёт отправлять не нужно');
    return !autoPhrase;
  }).join(' ');
}

function QuestSkeleton() {
  return (
    <div className="quest-skeleton-item">
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 10 }}>
        <div className="skel" style={{ height: 14, width: '55%' }} />
        <div className="skel" style={{ height: 14, width: '20%' }} />
      </div>
      <div className="skel" style={{ height: 11, width: '100%', marginBottom: 6 }} />
      <div className="skel" style={{ height: 11, width: '70%', marginBottom: 12 }} />
      <div style={{ display: 'flex', gap: 8 }}>
        <div className="skel" style={{ height: 20, width: 80 }} />
        <div className="skel" style={{ height: 20, width: 64 }} />
      </div>
    </div>
  );
}

const STATUS_LABELS = {
  DRAFT: 'В процессе',
  PENDING: 'На проверке',
  APPROVED: 'Выполнен',
  REJECTED: 'Отклонён',
  NEEDS_INFO: 'Нужны уточнения',
};
const STATUS_COLORS = {
  DRAFT: '#66bb6a',
  PENDING: '#ffa726',
  APPROVED: '#66bb6a',
  REJECTED: '#ef5350',
  NEEDS_INFO: '#ef5350',
};
// Ступени прогресса автоквестов: последний шаг начинается, когда осталось max(1, цель/10) (как QuestProgressNotifier в боте).
function lastStepAt(target) {
  return target - Math.max(1, Math.floor(target / 10));
}

function stepHint(progress, target) {
  const left = target - progress;
  if (progress >= lastStepAt(target)) return `🔥 Последний шаг — осталось ${left}`;
  if (progress * 2 >= target) return `✅ Половина пути пройдена — осталось ${left}`;
  return `Осталось ${left}`;
}

const CANCELABLE_STATUSES = ['DRAFT', 'PENDING', 'NEEDS_INFO', 'REJECTED'];

function QuestActions({ quest, detail, onChanged }) {
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);
  const [needsTagLink, setNeedsTagLink] = useState(null);
  const [photo, setPhoto] = useState(null);
  const [externalLink, setExternalLink] = useState('');
  const [comment, setComment] = useState('');
  const playLottie = useLottie();
  const playParticles = useParticles();
  const prevStatus = useRef(quest.submissionStatus);
  const status = detail?.submissionStatus;

  useEffect(() => {
    if (prevStatus.current !== 'APPROVED' && status === 'APPROVED') {
      playLottie?.();
      playParticles?.('questApproved', 4000);
    }
    prevStatus.current = status;
  }, [status]);

  if (!detail) {
    return <div className="quest-detail-loading">Загрузка...</div>;
  }

  const TAG_LINK_DEEPLINK = {
    NEEDS_BRAWL_TAG: { startParam: 'brawltag', label: '🏷️ Открыть бота и привязать тег' },
    NEEDS_CLASH_TAG: { startParam: 'clashtag', label: '🏷️ Открыть бота и привязать тег' },
    NEEDS_CLASH_ROYALE_TAG: { startParam: 'crtag', label: '🏷️ Открыть бота и привязать тег' },
    NEEDS_DOTA_LINK: { startParam: 'dotalink', label: '🏷️ Открыть бота и привязать тег' },
    NEEDS_CS2_LINK: { startParam: 'cs2link', label: '🏷️ Открыть бота и привязать тег' },
    NEEDS_PUBG_LINK: { startParam: 'pubglink', label: '🏷️ Открыть бота и привязать тег' },
    // Подписка на канал теперь проверяется при взятии квеста, а не сразу после анкеты (2026-09-14) —
    // тот же принцип, что и с привязкой тега: показать сообщение и увести в бота для завершения шага.
    NEEDS_CHANNEL_SUBSCRIPTION: { startParam: 'subscribe', label: '📢 Открыть бота и подписаться' },
  };

  function openBotForTag(startParam) {
    const tg = window.Telegram?.WebApp;
    const url = `https://t.me/invitetogamebot?start=${startParam}`;
    if (tg) tg.openTelegramLink(url); else window.open(url, '_blank', 'noopener');
  }

  async function handleTake() {
    setBusy(true);
    setMessage(null);
    setNeedsTagLink(null);
    try {
      const res = await takeQuest(quest.id);
      setMessage(res.message);
      setNeedsTagLink(TAG_LINK_DEEPLINK[res.status] || null);
      if (res.success) onChanged();
    } finally {
      setBusy(false);
    }
  }

  function openChannel(url) {
    const tg = window.Telegram?.WebApp;
    if (tg) {
      url.includes('t.me/') ? tg.openTelegramLink(url) : tg.openLink(url);
    } else {
      window.open(url, '_blank', 'noopener');
    }
  }

  // «Я подписался»: сервер сам проверит подписку и начислит награду (как кнопка в боте).
  async function handleChannelCheck() {
    setBusy(true);
    setMessage(null);
    try {
      const res = await channelCheckQuest(quest.id);
      setMessage(res.message);
      if (res.success) onChanged();
    } catch (e) {
      setMessage(e?.response?.data?.message || 'Не получилось, попробуйте чуть позже.');
    } finally {
      setBusy(false);
    }
  }

  async function handleSubmit() {
    if (!photo && !externalLink.trim()) {
      setMessage('Прикрепите скриншот или ссылку на подтверждение.');
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const res = await submitQuestReport(quest.id, { photo, externalLink, comment });
      setMessage(res.message);
      if (res.success) {
        setPhoto(null);
        setExternalLink('');
        setComment('');
        onChanged();
      }
    } catch (e) {
      setMessage(e?.response?.data?.message || 'Ошибка отправки. Попробуйте ещё раз.');
    } finally {
      setBusy(false);
    }
  }

  // Спонсорская подписка на канал: сдаётся кнопкой «Я подписался», повторно не проходится.
  const seatsLine = detail.channelCheck && detail.seatsTotal != null
    ? <div className="quest-seats">👥 Осталось мест: <b>{detail.seatsLeft}</b> из {detail.seatsTotal}</div>
    : null;
  if (detail.channelCheck) {
    if (status === 'APPROVED') {
      return (
        <div>
          {seatsLine}
          <div className="quest-status quest-status-approved"><i className="ti ti-circle-check"></i> Подписка подтверждена, квест выполнен и оплачен</div>
        </div>
      );
    }
    if (detail.campaignClosed) {
      return (
        <div>
          {seatsLine}
          <div className="quest-status quest-status-pending">😔 Квест закрыт — места заняты. Спонсор оплатил ограниченное число подписчиков.</div>
        </div>
      );
    }
    if (status === 'DRAFT') {
      return (
        <div className="quest-submit-form">
          {seatsLine}
          <div className="quest-go-hint">
            <div className="quest-go-hint-title">📢 Подпишитесь на канал и вернитесь сюда</div>
            <div className="quest-go-hint-text">Нажмите «Я подписался» — подписку проверим сами и сразу начислим награду.</div>
          </div>
          {detail.channelUrl && (
            <button className="quest-btn quest-btn-outline" disabled={busy} onClick={() => openChannel(detail.channelUrl)}>
              📢 Открыть канал
            </button>
          )}
          <button className="quest-btn" disabled={busy} onClick={handleChannelCheck}>
            {busy ? 'Проверяем...' : '✅ Я подписался'}
          </button>
          {message && <div className="quest-message">{message}</div>}
        </div>
      );
    }
  }

  // Партнёрский квест банка (ОТП и др.): всё ведёт менеджер, отчётов нет (как в боте, sendPartnerManagerMission).
  if (detail.bankRewardRub > 0) {
    if (status === 'DRAFT' && detail.partnerManagerFlow) {
      return (
        <div className="quest-submit-form">
          <div className="quest-go-hint">
            <div className="quest-go-hint-title">🚀 Квест взят!</div>
            <div className="quest-go-hint-text">
              {detail.partnerApplyUrl ? (
                <>
                  <p>Нажми «Оформить карту»: откроется страница с онлайн-заявкой.</p>
                  {detail.partnerApplyHint && <p>{detail.partnerApplyHint}</p>}
                  <p>Если что-то непонятно или ты уже выполнил все условия, напиши менеджеру.</p>
                </>
              ) : 'Напиши менеджеру: он проведёт по шагам в личных сообщениях.'}
            </div>
          </div>
          {detail.partnerApplyUrl && (
            <button className="quest-btn" onClick={() => openChannel(detail.partnerApplyUrl)}>💳 Оформить карту</button>
          )}
          {detail.partnerManagerUrl && (
            <button className="quest-btn quest-btn-outline" onClick={() => openChannel(detail.partnerManagerUrl)}>
              {detail.partnerApplyUrl ? '💬 Вопрос менеджеру' : '💬 Начать выполнять квест'}
            </button>
          )}
        </div>
      );
    }
    if (status === 'APPROVED') {
      return (
        <div>
          <div className="quest-status quest-status-approved"><i className="ti ti-circle-check"></i> Квест засчитан. Когда банк выплатит бонус, напиши менеджеру: он проверит и выдаст награду.</div>
          {detail.partnerManagerUrl && (
            <button className="quest-btn quest-btn-outline" style={{ marginTop: 8 }} onClick={() => openChannel(detail.partnerManagerUrl)}>💬 Написать менеджеру</button>
          )}
        </div>
      );
    }
    if (status === 'REJECTED') {
      return (
        <div>
          <div className="quest-status quest-status-pending">❌ Квест не засчитан{detail.moderatorComment ? `: ${detail.moderatorComment}` : ''}. Если считаешь это ошибкой, напиши менеджеру.</div>
          {detail.partnerManagerUrl && (
            <button className="quest-btn quest-btn-outline" style={{ marginTop: 8 }} onClick={() => openChannel(detail.partnerManagerUrl)}>💬 Написать менеджеру</button>
          )}
        </div>
      );
    }
  }

  // Квест-веха (oneTimePerAccount) и внешние партнёрские авто-квесты — единственные случаи, где
  // повтор в принципе невозможен, поэтому только для них тупиковое сообщение навсегда. Для всех
  // остальных APPROVED-квестов (обычных с суточным кулдауном и decay-пилота без кулдауна вовсе)
  // показываем кнопку "Пройти ещё раз" — если кулдаун ещё не истёк, об этом скажет ответ сервера
  // при нажатии ("Этот квест можно выполнять не чаще 1 раза в ..."), а не молчаливый дед-энд.
  if (status === 'APPROVED' && (detail.externalAutoApprove || detail.oneTimePerAccount)) {
    return <div className="quest-status quest-status-approved"><i className="ti ti-circle-check"></i> Квест выполнен и оплачен</div>;
  }

  if (status === 'APPROVED') {
    const note = detail.repeatableNoCooldownEligible
      ? 'Засчитано! Награда за следующее прохождение сегодня — меньше, завтра снова полная.'
      : 'Выполнен и оплачен. Когда закончится кулдаун на повтор — можно пройти ещё раз.';
    return (
      <div>
        <div className="quest-status quest-status-approved" style={{ marginBottom: 8 }}>
          <i className="ti ti-circle-check"></i> {note}
        </div>
        <button className="quest-btn" disabled={busy} onClick={handleTake}>
          {busy ? 'Секунду...' : 'Пройти ещё раз'}
        </button>
        {message && <div className="quest-message">{message}</div>}
      </div>
    );
  }

  if (status === 'PENDING') {
    return <div className="quest-status quest-status-pending"><i className="ti ti-clock"></i> Отчёт на проверке у модератора</div>;
  }

  if (detail.externalAutoApprove && status === 'DRAFT') {
    return <div className="quest-status quest-status-pending"><i className="ti ti-clock"></i> Ждём подтверждения от партнёра — отчёт отправлять не нужно</div>;
  }

  if (detail.brawlAutoVerify && status === 'DRAFT') {
    const target = detail.autoVerifyTarget;
    const progress = detail.autoVerifyProgress;
    if (target != null && progress != null) {
      const pct = Math.min(100, Math.round((progress / target) * 100));
      return (
        <>
        <GoPlayHint gameName={detail.gameName} auto />
        <div className="quest-status quest-status-pending">
          <div><i className="ti ti-clock"></i> Прогресс отслеживается автоматически по вашему аккаунту {detail.gameName}</div>
          <div className="quest-progress-track">
            <div className="quest-progress-fill" style={{ width: pct + '%' }} />
            {/* Ступени как в боте (QuestProgressNotifier): половина пути и последний шаг; только для целей от 5 */}
            {target >= 5 && (
              <>
                <span className={`quest-progress-tick ${progress * 2 >= target ? 'reached' : ''}`} style={{ left: '50%' }} />
                <span className={`quest-progress-tick ${progress >= lastStepAt(target) ? 'reached' : ''}`} style={{ left: (lastStepAt(target) / target * 100) + '%' }} />
              </>
            )}
          </div>
          <div className="quest-progress-label">{progress} / {target}</div>
          {target >= 5 && progress < target && (
            <div className="quest-progress-hint">{stepHint(progress, target)}</div>
          )}
        </div>
        </>
      );
    }
    return (
      <>
      <GoPlayHint gameName={detail.gameName} auto />
      <div className="quest-status quest-status-pending">
        <i className="ti ti-clock"></i> Прогресс отслеживается автоматически по вашему аккаунту {detail.gameName} — идёт первый замер…
      </div>
      </>
    );
  }

  if (status === 'DRAFT' || status === 'REJECTED' || status === 'NEEDS_INFO') {
    return (
      <div className="quest-submit-form">
        {status === 'DRAFT' && <GoPlayHint gameName={detail.gameName} />}
        {(status === 'REJECTED' || status === 'NEEDS_INFO') && detail.moderatorComment && (
          <div className="quest-mod-comment">
            <div className="quest-section-title">Комментарий модератора</div>
            <p>{detail.moderatorComment}</p>
          </div>
        )}
        <input
          type="file"
          accept="image/*,video/*"
          onChange={e => setPhoto(e.target.files?.[0] || null)}
          className="quest-file-input"
        />
        <input
          type="text"
          placeholder="Ссылка на подтверждение (необязательно)"
          value={externalLink}
          onChange={e => setExternalLink(e.target.value)}
          className="quest-text-input"
        />
        <textarea
          placeholder="Комментарий (необязательно)"
          value={comment}
          onChange={e => setComment(e.target.value)}
          className="quest-textarea"
        />
        <button className="quest-btn" disabled={busy} onClick={handleSubmit}>
          {busy ? 'Отправка...' : 'Отправить отчёт'}
        </button>
        {message && <div className="quest-message">{message}</div>}
      </div>
    );
  }

  return (
    <div>
      {seatsLine}
      <button className="quest-btn" disabled={busy} onClick={handleTake}>
        {busy ? 'Секунду...' : detail.missionQuest ? '🚀 Взять: мне 18+, я из РФ' : 'Взять квест'}
      </button>
      {message && (
        <div className="quest-message">
          {message}
          <PassUpsell message={message} />
          {needsTagLink && (
            <button className="quest-btn" style={{ marginTop: 8 }} onClick={() => openBotForTag(needsTagLink.startParam)}>
              {needsTagLink.label}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function QuestCard({ q, expanded, onToggle, details, onDetailChanged }) {
  const fundRatio = useFundRatio();
  // Статус берём из уже подгруженного детейла квеста, если он есть (details обновляется сразу после
  // взятия/сдачи отчёта), иначе — из общего списка квестов игры (details ещё не запрашивался). Без этого
  // бейдж в свёрнутой карточке "Все квесты" оставался бы старым до перезагрузки всего списка игры.
  const detail = details[q.id];
  const submissionStatus = detail?.submissionStatus ?? q.submissionStatus;
  const brawlAutoVerify = detail?.brawlAutoVerify ?? q.brawlAutoVerify;
  const externalAutoApprove = detail?.externalAutoApprove ?? q.externalAutoApprove;

  // Бейдж "🆕 Новое" на каждой карточке был избыточен, когда сразу много квестов одной игры
  // становятся "новыми" одновременно (например, все квесты игры добавлены одним деплоем) —
  // вместо флажка подсвечиваем фон карточки, и только пока квест ещё не взят (после взятия
  // важнее статус прохождения, чем то, что квест недавно добавили).
  const isNew = q.highlightNew && !submissionStatus;

  return (
    <div
      className={`quest-card ${q.gameName === 'UGC' ? 'q-ugc' : (CATEGORY_CLASS[q.category] || 'q-flat')} ${submissionStatus ? 'quest-card-taken' : ''} ${isNew ? 'quest-card-new' : ''}`}
      onClick={() => onToggle(q.id)}
    >
      {/* Шапка: категория + статус */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
          {q.sponsored ? (
            <span className="quest-cat-badge sponsored">💼 Спонсорский</span>
          ) : q.gameName === 'UGC' ? (
            <span className="quest-cat-badge ugc">🤳 Контент</span>
          ) : q.category ? (
            <span className={`quest-cat-badge ${CATEGORY_BADGE[q.category] || 'other'}`}>
              {q.category}
            </span>
          ) : null}
        </div>
        {submissionStatus && (
          <span className="quest-taken-badge" style={{ color: STATUS_COLORS[submissionStatus] }}>
            ● {submissionStatus === 'DRAFT' && (brawlAutoVerify || externalAutoApprove)
                ? 'Авто-отслеживание'
                : (STATUS_LABELS[submissionStatus] || submissionStatus)}
          </span>
        )}
      </div>

      <div className="quest-top">
        <div className="quest-title">{q.title}</div>
        <div className="quest-rewards">
          {q.bankRewardRub > 0 && <span className="reward-exc">💳 {q.bankRewardRub.toLocaleString('ru-RU')} ₽ от банка</span>}
          {q.bonusItem && <span className="reward-xp">🎁 {q.bonusItem.replace(' на 1 месяц', '')}</span>}
          {!(q.rewardCoins === 0 && q.bankRewardRub > 0) && (
          q.sponsorReward
            ? <span className="reward-exc">💎 {q.rewardCoins.toLocaleString()} EXCASH <span style={{ opacity: 0.7 }}>(≈ {Math.round(q.rewardCoins / 100).toLocaleString('ru-RU')} ₽)</span></span>
            : <span className="reward-exc"><i className="ti ti-coin"></i> {q.gameName === 'UGC' && !q.sponsored ? 'до 25 000' : q.rewardCoins.toLocaleString()} EXC{fundRatio != null && <> ({starsApprox(q.gameName === 'UGC' && !q.sponsored ? 25000 : q.rewardCoins, fundRatio)})</>}</span>
          )}
          {(details[q.id]?.rewardDiminished ?? q.rewardDiminished) && (
            <span className="reward-diminished">×½ лимит недели</span>
          )}
          {!(q.rewardXp === 0 && q.bankRewardRub > 0) && (
          <span className="reward-xp"><i className="ti ti-star"></i> {q.rewardXp} XP</span>
          )}
          {!q.sponsored && q.gameName !== 'UGC' && (q.ticketReward > 0 || CATEGORY_TICKETS[q.category]) && (
            <span className="reward-ticket">🎟 +{q.ticketReward > 0 ? q.ticketReward : CATEGORY_TICKETS[q.category]}</span>
          )}
        </div>
      </div>
      <div className="quest-meta">
        <span className="quest-duration">⏱ {q.durationDays === 1 ? '24 ч' : q.durationDays + ' дн'}</span>
        <span className="quest-platform">{q.platform}</span>
        {q.channelCheck && q.campaignClosed && !submissionStatus && <span className="quest-seats-chip closed">😔 Закрыт</span>}
        {q.channelCheck && !q.campaignClosed && q.seatsTotal != null && (
          <span className="quest-seats-chip">👥 {q.seatsLeft} из {q.seatsTotal}</span>
        )}
      </div>
      {expanded === q.id && (
        <div className="quest-detail" onClick={e => e.stopPropagation()}>
          {brawlAutoVerify && !externalAutoApprove ? (
            <p className="quest-desc">
              {(details[q.id]?.description ?? q.description)
                ? renderTextWithLinks(details[q.id]?.description ?? q.description)
                : simplifyAutoInstruction(details[q.id]?.instruction)}{' '}
              Прогресс засчитывается сам, ничего отправлять не нужно.
            </p>
          ) : (q.sponsored || details[q.id]?.sponsored) ? (
            <>
              {PARTNER_BANNERS[details[q.id]?.partnerKey ?? q.partnerKey] && (
                <img className="sponsor-banner" src={PARTNER_BANNERS[details[q.id]?.partnerKey ?? q.partnerKey]} alt="" />
              )}
              <SponsorDescription
                text={details[q.id]?.description ?? q.description}
                mission={!!details[q.id]?.missionQuest}
                instruction={details[q.id]?.instruction}
                requirements={details[q.id]?.requirements}
              />
            </>
          ) : (
            <>
              {q.gameName === 'UGC' && UGC_BANNERS[String(q.platform || '').toLowerCase()] && (
                <img className="sponsor-banner" src={UGC_BANNERS[String(q.platform).toLowerCase()]} alt="" onError={(e) => { e.currentTarget.style.display = 'none'; }} />
              )}
              <p className="quest-desc">{renderTextWithLinks(details[q.id]?.description ?? q.description)}</p>
            </>
          )}
          {!(brawlAutoVerify && !externalAutoApprove) && !details[q.id]?.missionQuest && details[q.id]?.instruction && (
            <>
              <div className="quest-section-title">Как выполнить</div>
              <p className="quest-instruction">{renderTextWithLinks(details[q.id].instruction)}</p>
            </>
          )}
          {q.gameName === 'UGC' && (
            <a className="quest-btn" style={{ display: 'block', textAlign: 'center', textDecoration: 'none', marginTop: 8 }}
              href="/ugc/egc-banner.mp4" download="EGC-banner.mp4" target="_blank" rel="noopener noreferrer">
              🎬 Скачать баннер
            </a>
          )}
          {!(brawlAutoVerify && !externalAutoApprove) && !details[q.id]?.missionQuest && details[q.id]?.requirements && (
            <>
              <div className="quest-section-title">
                {(details[q.id]?.brawlAutoVerify || details[q.id]?.externalAutoApprove) ? 'ℹ️ Информация' : 'Требования'}
              </div>
              <p className="quest-requirements">{renderTextWithLinks(details[q.id].requirements)}</p>
            </>
          )}
          {(details[q.id]?.rewardDiminished ?? q.rewardDiminished) && (
            <div className="quest-limit-note">{weeklyLimitText(q, details[q.id])}</div>
          )}
          {(details[q.id]?.egcPassBonusCoins > 0 || details[q.id]?.egcPassBonusXp > 0) && (
            <div className="quest-egc-note">
              ⭐ EGC Pass сверху к награде:{' '}
              {details[q.id].egcPassBonusCoins > 0 && `+${details[q.id].egcPassBonusCoins.toLocaleString()} EXC (+10%)`}
              {details[q.id].egcPassBonusCoins > 0 && details[q.id].egcPassBonusXp > 0 && ', '}
              {details[q.id].egcPassBonusXp > 0 && `+${details[q.id].egcPassBonusXp.toLocaleString()} XP (+5%)`}
            </div>
          )}
          {details[q.id]?.egcPassBoostExhausted && (
            <div className="quest-egc-note">⭐ EGC Pass: бонус +10% за этот месяц исчерпан — вернётся 1-го числа</div>
          )}
          <QuestActions
            quest={q}
            detail={details[q.id]}
            onChanged={() => onDetailChanged(q.id)}
          />
        </div>
      )}
    </div>
  );
}

function QuestBoostBanner() {
  const [boost, setBoost] = useState(null);
  const [countdown, setCountdown] = useState(null);

  useEffect(() => {
    getQuestBoost().then(setBoost).catch(() => setBoost(null));
  }, []);

  useEffect(() => {
    if (!boost?.active || !boost?.endsAt) { setCountdown(null); return; }
    const tick = () => {
      const diff = new Date(boost.endsAt).getTime() - Date.now();
      if (diff <= 0) { setCountdown(null); return; }
      const totalSeconds = Math.floor(diff / 1000);
      const h = String(Math.floor(totalSeconds / 3600)).padStart(2, '0');
      const m = String(Math.floor((totalSeconds % 3600) / 60)).padStart(2, '0');
      const s = String(totalSeconds % 60).padStart(2, '0');
      setCountdown(`${h}:${m}:${s}`);
    };
    tick();
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [boost?.active, boost?.endsAt]);

  if (!boost?.active || !countdown) return null;

  return (
    <div className="quest-boost-banner">
      {boost.percent != null && boost.percent % 100 !== 0
        ? `🔥 Буст выходных: +${boost.percent}% EXC за первые ${boost.maxQuests || 3} ${(boost.maxQuests || 3) < 5 ? 'квеста' : 'квестов'}!`
        : `🔥 Буст выходных ×${boost.multiplier}! EXC за квесты умножены`}
      <div className="quest-boost-banner-timer">Осталось: {countdown}</div>
    </div>
  );
}

function GameCard({ name, active, onClick }) {
  const [imgFailed, setImgFailed] = useState(false);

  return (
    <button className={`game-card ${active ? 'active' : ''}`} onClick={onClick}>
      {!imgFailed && (
        <img
          className="game-card-img"
          src={getGamePhotoUrl(name)}
          alt={name}
          loading="lazy"
          onError={() => setImgFailed(true)}
        />
      )}
      <div className="game-card-scrim" />
      <span className="game-card-name">{name}</span>
    </button>
  );
}

function AllQuestsView({ expanded, details, onToggle, onDetailChanged, initialSection, onSectionChange }) {
  const [games, setGames] = useState([]);
  const [selectedGame, setSelectedGame] = useState(null);
  const [quests, setQuests] = useState([]);
  const [loading, setLoading] = useState(false);
  const [ugcQuests, setUgcQuests] = useState([]);
  const [ugcLoading, setUgcLoading] = useState(false);
  const [sponsoredQuests, setSponsoredQuests] = useState([]);
  const [sponsoredLoading, setSponsoredLoading] = useState(false);
  const [activeSection, setActiveSection] = useState(initialSection === 'ads' ? 'ads' : initialSection === 'sponsored' ? 'sponsored' : 'gaming');
  const [sponsorOverview, setSponsorOverview] = useState(null);
  useEffect(() => { onSectionChange?.(activeSection); }, [activeSection]);
  const [sponsorCategory, setSponsorCategory] = useState(null);
  const questListRef = useRef(null);

  useEffect(() => {
    getGames().then(g => {
      const gameOnly = g.filter(name => name !== 'UGC');
      setGames(gameOnly);
      if (gameOnly.length > 0) setSelectedGame(gameOnly[0]);
    });
    setUgcLoading(true);
    getQuests('UGC').then(setUgcQuests).finally(() => setUgcLoading(false));
    setSponsoredLoading(true);
    getSponsoredQuests().then(setSponsoredQuests).catch(() => setSponsoredQuests([])).finally(() => setSponsoredLoading(false));
    getSponsoredOverview().then(setSponsorOverview).catch(() => setSponsorOverview(null));
  }, []);

  // После выполнения квеста суммы «до N ₽» пересчитываем (выполненные в них не входят)
  function handleSponsorDetailChanged(id) {
    onDetailChanged(id);
    getSponsoredOverview().then(setSponsorOverview).catch(() => {});
    getSponsoredQuests().then(setSponsoredQuests).catch(() => {});
  }

  useEffect(() => {
    if (!selectedGame) return;
    setLoading(true);
    getQuests(selectedGame).then(setQuests).finally(() => setLoading(false));
  }, [selectedGame]);

  // Клик по карточке игры сразу прокручивает к списку квестов этой игры ниже.
  function handleSelectGame(g) {
    setSelectedGame(g);
    requestAnimationFrame(() => {
      questListRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
  }

  const flatQuests = quests.filter(q => !q.category);
  const grouped = CATEGORY_ORDER.reduce((acc, cat) => {
    const list = quests.filter(q => q.category === cat);
    if (list.length) acc[cat] = list;
    return acc;
  }, {});

  // games уже отсортирован бэкендом по интересу пользователя (sortGamesByInterest) — верхние N
  // считаем "лучшими" без доп. логики на фронте.
  const topGames = games.slice(0, 3);
  const restGames = games.slice(3);

  return (
    <>
      <QuestBoostBanner />

      <div className="view-toggle quest-section-tabs">
        {QUEST_SECTIONS.map(s => (
          <button
            key={s.key}
            className={`view-tab ${activeSection === s.key ? 'active' : ''}`}
            onClick={() => setActiveSection(s.key)}
          >
            {s.label}
          </button>
        ))}
      </div>

      {activeSection === 'gaming' && (
        <>
          {topGames.length > 0 && (
            <div className="game-section">
              <div className="game-section-title">🔥 Лучшие игры</div>
              <div className="game-grid">
                {topGames.map(g => (
                  <GameCard key={g} name={g} active={selectedGame === g} onClick={() => handleSelectGame(g)} />
                ))}
              </div>
            </div>
          )}
          {restGames.length > 0 && (
            <div className="game-section">
              <div className="game-section-title">Все игры</div>
              <div className="game-grid">
                {restGames.map(g => (
                  <GameCard key={g} name={g} active={selectedGame === g} onClick={() => handleSelectGame(g)} />
                ))}
              </div>
            </div>
          )}

          <div ref={questListRef} className="game-section">
            <div className="game-section-title">
              🎯 Доступные квесты{selectedGame ? ` — ${selectedGame}` : ''}
            </div>
            {loading && (
              <div className="category-section">
                {[1,2,3].map(i => <QuestSkeleton key={i} />)}
              </div>
            )}
            {!loading && flatQuests.length > 0 && (
              <div className="category-section">
                {flatQuests.map(q => (
                  <QuestCard key={q.id} q={q} expanded={expanded} onToggle={onToggle} details={details} onDetailChanged={onDetailChanged} />
                ))}
              </div>
            )}
            {!loading && Object.entries(grouped).map(([cat, list]) => (
              <div key={cat} className="category-section">
                <div className="category-header" style={{ color: CATEGORY_COLORS[cat] }}>{cat}</div>
                {list.map(q => (
                  <QuestCard key={q.id} q={q} expanded={expanded} onToggle={onToggle} details={details} onDetailChanged={onDetailChanged} />
                ))}
              </div>
            ))}
          </div>
        </>
      )}

      {activeSection === 'sponsored' && (
        <>
          {sponsoredLoading && !sponsorOverview && <div className="page-center">Загрузка...</div>}
          {!sponsorCategory && sponsorOverview && (
            <div className="category-section">
              <img className="sponsor-banner" src="/sponsored/quests.jpg" alt="" />
              <div className="sponsor-title">💼 Спонсорские квесты</div>
              {sponsorOverview.prizeRub > 0 && (
                <div className="sponsor-prize">
                  🎁 Выполни все квесты из этого раздела и получи суммарно более <b>{sponsorOverview.prizeRub.toLocaleString('ru-RU')} ₽</b> на карту.
                  <div className="sponsor-prize-note">Каждый квест доступен один раз на аккаунт.</div>
                </div>
              )}
              <div className="sponsor-intro">
                🤝 Деньги спонсоров идут в фонд выплат, а от его размера зависит курс вывода для всех игроков.
                <details style={{ marginTop: 6 }}>
                  <summary style={{ cursor: 'pointer' }}>Подробнее</summary>
                  <div style={{ marginTop: 6 }}>
                    Спонсоры — одна из причин, по которой проект работает и платит вам реальные деньги. Чем лояльнее вы относитесь к таким квестам, тем стабильнее проект может выплачивать вам дальше. Чаще заглядывайте сюда и выполняйте задания: чем больше спонсоров мы привлечём вместе, тем выгоднее будет курс.
                  </div>
                </details>
              </div>
              <div className="sponsor-pick">Выберите тип задания:</div>
              {sponsorOverview.categories.map(c => (
                <button key={c.key} className="sponsor-cat-btn" onClick={() => setSponsorCategory(c.key)}>
                  {c.label} · {c.count}
                </button>
              ))}
              {sponsorOverview.picks?.length > 0 && (
                <button className="sponsor-cat-btn" onClick={() => setSponsorCategory('picks')}>🤝 Экси рекомендует</button>
              )}
            </div>
          )}
          {sponsorCategory === 'picks' && sponsorOverview && (
            <div className="category-section">
              <button className="sponsor-back" onClick={() => setSponsorCategory(null)}>← Разделы</button>
              <div className="sponsor-title">🤝 Экси рекомендует</div>
              <div className="sponsor-prize-note" style={{ marginBottom: 10 }}>Это не квесты: EXC за такие переходы не начисляются. Экси просто показывает то, что нашёл за пределами игр клуба.</div>
              {sponsorOverview.picks.map(p => (
                <div key={p.key} className="quest-card" style={{ cursor: 'default' }}>
                  <div className="quest-title">🎮 {p.title}</div>
                  <p className="quest-desc" style={{ whiteSpace: 'pre-line' }}>{p.description}</p>
                  {p.adLabel && <ExpandableQuote title="Реклама"><i>{p.adLabel}</i></ExpandableQuote>}
                  <button className="quest-btn" onClick={() => openUrl(p.url)}>{p.buttonLabel}</button>
                </div>
              ))}
            </div>
          )}
          {sponsorCategory && sponsorCategory !== 'picks' && sponsorOverview && (() => {
            const cat = sponsorOverview.categories.find(c => c.key === sponsorCategory);
            const list = sponsoredQuests.filter(q => q.sponsorCategory === sponsorCategory);
            return (
              <div className="category-section">
                <button className="sponsor-back" onClick={() => setSponsorCategory(null)}>← Разделы</button>
                <img className="sponsor-banner" src={`/sponsored/${sponsorCategory}.jpg`} alt="" />
                <div className="sponsor-title">💼 Спонсорские квесты · {cat?.title}</div>
                {cat && (cat.prizeRub > 0 || cat.premium) && (
                  <div className="sponsor-prize">
                    🎁 В этом разделе: {cat.prizeRub > 0 ? `до ${cat.prizeRub.toLocaleString('ru-RU')} ₽` : ''}{cat.premium ? `${cat.prizeRub > 0 ? ' и ' : ''}Telegram Premium` : ''}
                  </div>
                )}
                {list.length === 0 && <div className="quest-empty-section">👀 Скоро здесь появятся новые задания.</div>}
                {list.map(q => (
                  <QuestCard key={q.id} q={q} expanded={expanded} onToggle={onToggle} details={details} onDetailChanged={handleSponsorDetailChanged} />
                ))}
              </div>
            );
          })()}
          {/* Запасной вид, пока бэкенд без разделов (старая версия): плоский список, как раньше */}
          {!sponsorOverview && !sponsoredLoading && sponsoredQuests.length === 0 && (
            <div className="quest-empty-section">👀 Спонсорские квесты появятся скоро</div>
          )}
          {!sponsorOverview && !sponsoredLoading && sponsoredQuests.length > 0 && (
            <div className="category-section">
              {sponsoredQuests.map(q => (
                <QuestCard key={q.id} q={q} expanded={expanded} onToggle={onToggle} details={details} onDetailChanged={handleSponsorDetailChanged} />
              ))}
            </div>
          )}
        </>
      )}

      {activeSection === 'ugc' && (
        <>
          {ugcLoading && <div className="page-center">Загрузка...</div>}
          {!ugcLoading && ugcQuests.length === 0 && (
            <div className="quest-empty-section">Нет активных контент-квестов</div>
          )}
          {!ugcLoading && ugcQuests.length > 0 && (
            <div className="category-section">
              {ugcQuests.map(q => (
                <QuestCard key={q.id} q={q} expanded={expanded} onToggle={onToggle} details={details} onDetailChanged={onDetailChanged} />
              ))}
            </div>
          )}
        </>
      )}

      {activeSection === 'ads' && (
        <div className="category-section">
          <AdRewardCard placement="quests" />
        </div>
      )}
    </>
  );
}

function MyQuestsView({ expanded, details, onToggle, onDetailChanged, kind, onKindChange }) {
  const fundRatio = useFundRatio();
  const [myQuests, setMyQuests] = useState(null);
  const [error, setError] = useState(null);
  const [cancelBusy, setCancelBusy] = useState(null);
  // onDetailChanged (=loadDetail в родителе) не обёрнут в useCallback — новая ссылка на каждый
  // рендер. Держим текущие expanded/onDetailChanged в ref, чтобы не пересоздавать интервал ниже
  // на каждый чих родителя (эффект с пустыми зависимостями настраивается один раз при монтировании).
  const expandedRef = useRef(expanded);
  const onDetailChangedRef = useRef(onDetailChanged);
  expandedRef.current = expanded;
  onDetailChangedRef.current = onDetailChanged;

  function reload() {
    setError(null);
    getMyQuests().then(setMyQuests).catch(() => setError('Не удалось загрузить квесты. Попробуйте ещё раз.'));
  }

  useEffect(() => {
    // Авто-верификация (Brawl Stars/Clash/Dota/CS2/PUBG) засчитывает квест в фоне на сервере —
    // без опроса игрок видел бы старый статус ("Авто-отслеживание", 0/N), пока не выйдет и не
    // зайдёт заново на этот экран. Обновление в чате бота приходит всегда, но если игрок сидит
    // именно тут в ожидании — экран должен обновляться сам. reload() обновляет сам список (бейджи
    // статуса свёрнутых карточек), а onDetailChanged(expanded) — прогресс-бар РАЗВЁРНУТОЙ карточки
    // (он читается из отдельного details[id], а не из списка) — без второго вызова прогресс-бар
    // у открытой карточки не двигался бы, пока список рядом уже обновился.
    function tick() {
      reload();
      if (expandedRef.current != null) onDetailChangedRef.current(expandedRef.current);
    }
    tick();
    const interval = setInterval(tick, 15000);
    function onVisible() { if (document.visibilityState === 'visible') tick(); }
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      clearInterval(interval);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, []);

  async function handleCancel(submissionId, e) {
    e.stopPropagation();
    setCancelBusy(submissionId);
    try {
      await cancelMyQuest(submissionId);
    } catch (err) {
      // Раньше ошибка глотала молча: игрок жал «Отменить», ничего не менялось и непонятно почему
      const msg = err?.response?.data?.message || 'Не удалось отменить квест. Попробуйте ещё раз.';
      window.alert(msg);
    } finally {
      setCancelBusy(null);
      reload();
    }
  }

  if (error) {
    return <div className="page-center error-msg">{error}</div>;
  }

  if (myQuests === null) {
    return <div className="page-center">Загрузка...</div>;
  }

  const gameCount = myQuests.filter(m => !m.sponsoredKind).length;
  const sponsorCount = myQuests.filter(m => m.sponsoredKind).length;
  const shownQuests = myQuests.filter(m => !!m.sponsoredKind === (kind === 'sponsored'));
  const kindToggle = (
    <div className="view-toggle" style={{ padding: '8px 0 0' }}>
      <button className={`view-tab ${kind !== 'sponsored' ? 'active' : ''}`} onClick={() => onKindChange('game')}>🎮 Мои игровые{gameCount > 0 ? ` · ${gameCount}` : ''}</button>
      <button className={`view-tab ${kind === 'sponsored' ? 'active' : ''}`} onClick={() => onKindChange('sponsored')}>💼 Мои спонсорские{sponsorCount > 0 ? ` · ${sponsorCount}` : ''}</button>
    </div>
  );

  if (shownQuests.length === 0) {
    return (
      <div>
        {kindToggle}
        <div className="page-center">
          📭 {kind === 'sponsored'
            ? 'У вас нет спонсорских квестов в работе. Откройте «Все квесты» → «Спонсоры» и возьмите первое задание.'
            : 'У вас нет игровых квестов в работе. Откройте «Все квесты» и возьмите первое задание.'}
        </div>
      </div>
    );
  }

  return (
    <div className="category-section" style={{ marginTop: 12 }}>
      {kindToggle}
      {shownQuests.map(m => (
        <div key={m.submissionId} className="quest-card" onClick={() => onToggle(m.questId)}>
          <div className="quest-top">
            <div className="quest-title">{m.title}</div>
            <div className="quest-rewards">
              {m.bankRewardRub > 0 && <span className="reward-exc">💳 {m.bankRewardRub.toLocaleString('ru-RU')} ₽ от банка</span>}
              {!(m.rewardCoins === 0 && m.bankRewardRub > 0) && (
                m.sponsorReward
                  ? <span className="reward-exc">💎 {m.rewardCoins.toLocaleString()} EXCASH <span style={{ opacity: 0.7 }}>(≈ {Math.round(m.rewardCoins / 100).toLocaleString('ru-RU')} ₽)</span></span>
                  : <span className="reward-exc"><i className="ti ti-coin"></i> {m.rewardCoins.toLocaleString()} EXC{fundRatio != null && <> ({starsApprox(m.rewardCoins, fundRatio)})</>}</span>
              )}
              {!(m.rewardXp === 0 && m.bankRewardRub > 0) && (
                <span className="reward-xp"><i className="ti ti-star"></i> {m.rewardXp} XP</span>
              )}
            </div>
          </div>
          <div className="quest-meta">
            <span style={{ color: STATUS_COLORS[m.status] }}>
              ● {m.status === 'DRAFT' && (m.brawlAutoVerify || m.externalAutoApprove)
                  ? 'Авто-отслеживание'
                  : (STATUS_LABELS[m.status] || m.status)}
            </span>
            <span className="quest-platform">{m.gameName}</span>
          </div>
          {expanded === m.questId && (
            <div className="quest-detail" onClick={e => e.stopPropagation()}>
              <QuestActions
                quest={{ id: m.questId, title: m.title, rewardXp: m.rewardXp, rewardCoins: m.rewardCoins }}
                detail={details[m.questId]}
                onChanged={() => { onDetailChanged(m.questId); reload(); }}
              />
              {CANCELABLE_STATUSES.includes(m.status) && (
                <button
                  className="quest-btn quest-btn-secondary"
                  disabled={cancelBusy === m.submissionId}
                  onClick={e => handleCancel(m.submissionId, e)}
                >
                  {cancelBusy === m.submissionId ? 'Отмена...' : 'Отменить квест'}
                </button>
              )}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

function TournamentLeaderboard({ id }) {
  const [entries, setEntries] = useState(null);

  useEffect(() => {
    getTournamentLeaderboard(id).then(setEntries).catch(() => setEntries([]));
  }, [id]);

  if (entries === null) return <div className="quest-detail-loading">Загрузка...</div>;
  if (entries.length === 0) return <div className="quest-message">Пока никто не записался.</div>;

  return (
    <div className="category-section" style={{ padding: 0, marginTop: 8 }}>
      {entries.slice(0, 20).map(e => (
        <div key={e.rank} className="quest-meta" style={{ padding: '6px 0' }}>
          <span>{e.rank}. {e.nickname}</span>
          {e.prizeExc > 0 && <span className="reward-exc">🏆 +{e.prizeExc} EXC</span>}
        </div>
      ))}
      {entries.length > 20 && <div className="quest-message">...и ещё {entries.length - 20}</div>}
    </div>
  );
}

function TournamentView() {
  const [tournament, setTournament] = useState(undefined);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(null);
  const [bannerFailed, setBannerFailed] = useState(false);

  function reload() {
    getTournament().then(setTournament).catch(() => setTournament(null));
  }

  useEffect(() => { reload(); }, []);
  useEffect(() => { setBannerFailed(false); }, [tournament?.id]);

  async function handleJoin() {
    setBusy(true);
    setMessage(null);
    try {
      const res = await joinTournament(tournament.id);
      setMessage(res.message);
      if (res.success) reload();
    } finally {
      setBusy(false);
    }
  }

  if (tournament === undefined) return <div className="page-center">Загрузка...</div>;
  if (tournament === null) {
    return <div className="page-center">⏳ Активных турниров нет. Следите за новостями клуба!</div>;
  }

  const isReg = tournament.status === 'REGISTRATION';
  const isActive = tournament.status === 'ACTIVE';

  return (
    <div className="category-section" style={{ marginTop: 12 }}>
      <div className="quest-card" style={{ cursor: 'default' }}>
        {!bannerFailed && (
          <img
            className="tournament-banner"
            src={getTournamentPhotoUrl(tournament.id)}
            alt={tournament.name}
            loading="lazy"
            onError={() => setBannerFailed(true)}
          />
        )}
        <div className="quest-top">
          <div className="quest-title">📌 {tournament.name}</div>
        </div>
        <div className="quest-meta" style={{ marginBottom: 8 }}>
          {tournament.gameName && <span>🎮 {tournament.gameName}</span>}
          <span>👥 {tournament.entryCount} участников</span>
        </div>
        {tournament.description && (
          <p className="quest-desc" style={{ margin: '0 0 8px' }}>{tournament.description}</p>
        )}
        <p className="quest-desc" style={{ margin: '0 0 8px' }}>
          💰 Взнос: <b>{tournament.entryFeeExc.toLocaleString()} EXC</b><br />
          🏅 Призовой фонд: <b>{tournament.prizePoolExc.toLocaleString()} EXC</b><br />
          {tournament.startDate && <>🔒 Закрытие регистрации: {tournament.startDate} (UTC)<br /></>}
          {tournament.endDate && <>⏰ Финиш: {tournament.endDate} (UTC)<br /></>}
        </p>
        {(isReg || isActive) && (
          <p className="quest-desc" style={{ margin: '0 0 8px' }}>
            {isActive ? '🔥 Турнир идёт! Выполняйте квесты — побеждает тот, кто выполнит больше всего.' : '📋 Идёт регистрация!'}<br />
            🥇 1 место — 60% призового фонда<br />
            🥈-🥉 2–10 места — остаток фонда поровну
          </p>
        )}

        {isReg && !tournament.entered && (
          <button className="quest-btn" disabled={busy} onClick={handleJoin}>
            {busy ? 'Секунду...' : `⚔️ Участвовать (${tournament.entryFeeExc.toLocaleString()} EXC)`}
          </button>
        )}
        {tournament.entered && <div className="quest-status quest-status-approved" style={{ marginTop: 16 }}>✅ Вы зарегистрированы!</div>}
        {message && <div className="quest-message">{message}</div>}

        {(tournament.entered || isActive) && (
          <>
            <div className="quest-section-title" style={{ marginTop: 12 }}>Список участников</div>
            <TournamentLeaderboard id={tournament.id} />
          </>
        )}
      </div>
    </div>
  );
}

export default function QuestsPage() {
  const [searchParams] = useSearchParams();
  const initialSection = searchParams.get('section');
  const [view, setView] = useState('all');
  const [expanded, setExpanded] = useState(null);
  const [details, setDetails] = useState({});
  // Слоты квестов (14.11.2026): игрок не видел, что у него есть второй слот. Обновляем при смене вкладки и раз в 20 секунд.
  const [slots, setSlots] = useState(null);
  const [allSection, setAllSection] = useState('gaming');
  const [myKind, setMyKind] = useState('game');
  // У спонсорских квестов нет слотов и лимитов (как в боте): строку слотов там не показываем
  const hideSlots = (view === 'all' && allSection === 'sponsored') || (view === 'mine' && myKind === 'sponsored');
  useEffect(() => {
    let cancelled = false;
    const load = () => getQuestSlots().then(s => { if (!cancelled) setSlots(s); }).catch(() => {});
    load();
    const t = setInterval(load, 20000);
    return () => { cancelled = true; clearInterval(t); };
  }, [view]);

  function loadDetail(id) {
    getQuestDetail(id).then(d => setDetails(prev => ({ ...prev, [id]: d })));
  }

  function toggleExpand(id) {
    if (expanded === id) {
      setExpanded(null);
      return;
    }
    setExpanded(id);
    loadDetail(id);
  }

  function switchView(v) {
    setView(v);
    setExpanded(null);
  }

  return (
    <div className="quests-page">
      <div className="view-toggle">
        <button className={`view-tab ${view === 'all' ? 'active' : ''}`} onClick={() => switchView('all')}>
          Все квесты
        </button>
        <button className={`view-tab ${view === 'mine' ? 'active' : ''}`} onClick={() => switchView('mine')}>
          Мои квесты
        </button>
        <button className={`view-tab ${view === 'tournament' ? 'active' : ''}`} onClick={() => switchView('tournament')}>
          Турнир
        </button>
      </div>

      {slots && view !== 'tournament' && !hideSlots && (
        <div className="slots-line">
          📂 Слоты квестов: <b>{slots.used} из {slots.max}</b>
          {slots.fromPass ? ' · 3-й слот по EGC Pass' : slots.permanent ? ' · доп. слот навсегда' : slots.extraUntil ? ` · доп. слот до ${slots.extraUntil}` : ''}
        </div>
      )}

      {view === 'all' && <AllQuestsView expanded={expanded} details={details} onToggle={toggleExpand} onDetailChanged={loadDetail} initialSection={initialSection} onSectionChange={setAllSection} />}
      {view === 'mine' && <MyQuestsView expanded={expanded} details={details} onToggle={toggleExpand} onDetailChanged={loadDetail} kind={myKind} onKindChange={setMyKind} />}
      {view === 'tournament' && <TournamentView />}
    </div>
  );
}
