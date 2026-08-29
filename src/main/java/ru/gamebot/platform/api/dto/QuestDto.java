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
    private int ticketReward;
    private boolean councilOnly;
    private boolean sponsored;

    /** Внешний авто-квест (партнёрская сеть): вместо отчёта нужно просто перейти по ссылке. */
    private boolean externalAutoApprove;

    /** Brawl Stars авто-верификация: прогресс отслеживается по battlelog/трофеям, отчёт не нужен. */
    private boolean brawlAutoVerify;

    /** null, если пользователь ещё не брал этот квест */
    private String submissionStatus;
}
