package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

public class LeagueRewardEvent extends ApplicationEvent {

    private final Long telegramId;
    private final String leagueName;
    private final long excPrize;
    private final int weeklyXp;

    public LeagueRewardEvent(Object source, Long telegramId, String leagueName, long excPrize, int weeklyXp) {
        super(source);
        this.telegramId = telegramId;
        this.leagueName = leagueName;
        this.excPrize = excPrize;
        this.weeklyXp = weeklyXp;
    }

    public Long getTelegramId() { return telegramId; }
    public String getLeagueName() { return leagueName; }
    public long getExcPrize() { return excPrize; }
    public int getWeeklyXp() { return weeklyXp; }
}
