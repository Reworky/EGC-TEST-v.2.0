package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

public class WeeklyDigestActiveEvent extends ApplicationEvent {

    private final Long telegramId;
    private final long completedQuests;
    private final long earnedExc;
    private final long weeklyXp;
    private final String leagueName;
    private final int weeklyRank;
    private final long xpToNextLevel;
    /** Сколько EXC/XP игрок получил бы за неделю с EGC Pass (0, если пасс уже активен) - для мягкой строки-предложения в дайджесте. */
    private final long passBonusExc;
    private final long passBonusXp;

    public WeeklyDigestActiveEvent(Object source, Long telegramId, long completedQuests,
                                    long earnedExc, long weeklyXp, String leagueName,
                                    int weeklyRank, long xpToNextLevel, long passBonusExc, long passBonusXp) {
        super(source);
        this.telegramId = telegramId;
        this.completedQuests = completedQuests;
        this.earnedExc = earnedExc;
        this.weeklyXp = weeklyXp;
        this.leagueName = leagueName;
        this.weeklyRank = weeklyRank;
        this.xpToNextLevel = xpToNextLevel;
        this.passBonusExc = passBonusExc;
        this.passBonusXp = passBonusXp;
    }

    public Long getTelegramId() { return telegramId; }
    public long getCompletedQuests() { return completedQuests; }
    public long getEarnedExc() { return earnedExc; }
    public long getWeeklyXp() { return weeklyXp; }
    public String getLeagueName() { return leagueName; }
    public int getWeeklyRank() { return weeklyRank; }
    public long getXpToNextLevel() { return xpToNextLevel; }
    public long getPassBonusExc() { return passBonusExc; }
    public long getPassBonusXp() { return passBonusXp; }
}
