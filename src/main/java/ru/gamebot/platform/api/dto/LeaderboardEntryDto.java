package ru.gamebot.platform.api.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LeaderboardEntryDto {
    private int rank;
    private Long telegramId;
    private String nickname;
    private String levelName;
    private String profileTitle;
    /** XP, релевантный типу рейтинга: недельный XP для type=weekly, общий — для overall. */
    private long xp;
    private int completedQuests;
    /** Активный EGC Pass: в рейтинге рядом с ником показывается ⭐ (статус виден всем, 23.10.2026). */
    private boolean egcPass;
    /** Только для "я" в недельном рейтинге — текущая лига и её приз (null для overall и для записей таблицы). */
    private String leagueName;
    private long leagueExcPrize;
}
