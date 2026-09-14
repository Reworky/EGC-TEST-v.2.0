package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Игрок держит серию входов (streakDays >= 2), но ещё не заходил сегодня — серия сгорит в полночь,
 *  если не отправить /start до конца дня. См. WeeklyResetScheduler.checkStreaksAtRisk(). */
public class StreakAtRiskEvent extends ApplicationEvent {

    private final Long telegramId;
    private final int streakDays;

    public StreakAtRiskEvent(Object source, Long telegramId, int streakDays) {
        super(source);
        this.telegramId = telegramId;
        this.streakDays = streakDays;
    }

    public Long getTelegramId() { return telegramId; }
    public int getStreakDays() { return streakDays; }
}
