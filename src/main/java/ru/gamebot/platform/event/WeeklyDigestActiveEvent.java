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

    public WeeklyDigestActiveEvent(Object source, Long telegramId, long completedQuests,
                                    long earnedExc, long weeklyXp, String leagueName,
                                    int weeklyRank, long xpToNextLevel) {
        super(source);
        this.telegramId = telegramId;
        this.completedQuests = completedQuests;
        this.earnedExc = earnedExc;
        this.weeklyXp = weeklyXp;
        this.leagueName = leagueName;
        this.weeklyRank = weeklyRank;
        this.xpToNextLevel = xpToNextLevel;
    }

    public Long getTelegramId() { return telegramId; }
    public long getCompletedQuests() { return completedQuests; }
    public long getEarnedExc() { return earnedExc; }
    public long getWeeklyXp() { return weeklyXp; }
    public String getLeagueName() { return leagueName; }
    public int getWeeklyRank() { return weeklyRank; }
    public long getXpToNextLevel() { return xpToNextLevel; }
}
