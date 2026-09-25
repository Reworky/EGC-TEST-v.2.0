package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Сообщение игроку, затихшему на 4-13 дней: без EXC, только «появились новые квесты» (см. WeeklyResetScheduler.checkSilentGap). */
public class SilentGapNudgeEvent extends ApplicationEvent {

    private final Long telegramId;
    private final long newQuestsCount;
    private final long daysSince;

    public SilentGapNudgeEvent(Object source, Long telegramId, long newQuestsCount, long daysSince) {
        super(source);
        this.telegramId = telegramId;
        this.newQuestsCount = newQuestsCount;
        this.daysSince = daysSince;
    }

    public Long getTelegramId() { return telegramId; }
    public long getNewQuestsCount() { return newQuestsCount; }
    public long getDaysSince() { return daysSince; }
}
