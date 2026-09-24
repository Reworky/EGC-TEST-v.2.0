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

    /** Недельный лимит (правило 3.4) по этой игре+категории уже исчерпан — rewardCoins здесь уже
     *  вдвое меньше номинала, фронтенд показывает пометку, чтобы игрок видел причину до взятия квеста. */
    private boolean rewardDiminished;
    private int weeklyLimit;

    private int ticketReward;
    private boolean councilOnly;
    private boolean sponsored;

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
}
