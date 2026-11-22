package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QuestDto {
    private Long id;
    private String title;
    private String description;
    private String gameName;
    private String category;
    private String platform;
    private int durationDays;
    private long rewardXp;
    private long rewardCoins;
    /** Награда идёт на спонсорский баланс EXCASH (банковская миссия или партнёрский квест, выключатель включён): мини-апп показывает «💎 N EXCASH» вместо «N EXC». */
    private boolean sponsorReward;
    /** UGC-квест, за который сейчас (пилот «UGC в EXCASH») платят EXCASH от 500 просмотров: мини-апп пишет «до 25 000 EXCASH» вместо EXC. */
    private boolean ugcExcash;

    /** Недельный лимит (правило 3.4) по этой игре+категории уже исчерпан — rewardCoins здесь уже
     *  вдвое меньше номинала, фронтенд показывает пометку, чтобы игрок видел причину до взятия квеста. */
    private boolean rewardDiminished;
    private int weeklyLimit;

    private int ticketReward;
    private boolean councilOnly;
    private boolean sponsored;
    /** Раздел спонсорского квеста (debit/games/credit/mobile/gigs); null - не спонсорский. */
    private String sponsorCategory;
    /** Рубли от банка по партнёрской миссии (0 - нет) и подарок (Telegram Premium и т.п.): вместо «0 EXC» на карточке. */
    private long bankRewardRub;
    private String bonusItem;
    /** Ключ партнёрской миссии банка (otp, tblack, tmobile) - по нему мини-апп показывает баннер карточки; null - не партнёрский. */
    private String partnerKey;

    /** См. Quest.isEffectivelyNew — карточка подсвечивается как новая в Mini App (парность с sendQuestList
     * в боте): квест создан в последние 7 дней ИЛИ ещё не истёк редакторский буст highlightNewUntil. */
    private boolean highlightNew;

    /** Внешний авто-квест (партнёрская сеть): вместо отчёта нужно просто перейти по ссылке. */
    private boolean externalAutoApprove;

    /** Название сохранено для обратной совместимости с фронтендом — на деле покрывает авто-верификацию
     *  по API любой из трёх игр (Brawl Stars/Clash of Clans/Clash Royale), не только Brawl. Прогресс
     *  отслеживается автоматически, отчёт не нужен. */
    private boolean brawlAutoVerify;

    /** null, если пользователь ещё не брал этот квест */
    private String submissionStatus;

    /** Спонсорская подписка на канал: сдаётся кнопкой «Я подписался» (проверка через бота), есть счётчик мест. */
    private boolean channelCheck;
    /** Осталось мест / всего мест кампании; null - лимита нет. */
    private Integer seatsLeft;
    private Integer seatsTotal;
    /** Кампания закрыта (спонсор остановил или места заняты) - брать/сдавать нельзя. */
    private boolean campaignClosed;
}
