package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QuestDetailDto {
    private Long id;
    private String title;
    private String description;
    private String instruction;
    private String requirements;
    private String gameName;
    private String category;
    private String platform;
    private int durationDays;
    private long rewardXp;
    private long rewardCoins;
    private int ticketReward;
    private boolean councilOnly;

    /** Внешний авто-квест (партнёрская сеть): вместо отчёта нужно просто перейти по ссылке из instruction. */
    private boolean externalAutoApprove;

    /** Название сохранено для обратной совместимости с фронтендом — на деле покрывает авто-верификацию
     *  по API любой из трёх игр (Brawl Stars/Clash of Clans/Clash Royale), не только Brawl. Прогресс
     *  отслеживается автоматически, отчёт не нужен. */
    private boolean brawlAutoVerify;

    /** Только для авто-верифицируемых квестов с активной заявкой. null = ещё не авто-квест, либо
     *  (если target тоже null) идёт первый замер после взятия — baseline ещё не зафиксирован API. */
    private Integer autoVerifyProgress;
    private Integer autoVerifyTarget;

    /** null, если пользователь ещё не брал этот квест */
    private String submissionStatus;
    private String moderatorComment;
    private String expiresAt;
}
