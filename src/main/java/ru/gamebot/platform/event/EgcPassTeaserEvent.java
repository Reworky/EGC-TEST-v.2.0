package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Разовое предложение EGC Pass игроку с 10+ квестами (см. WeeklyResetScheduler.checkEgcPassTeaser). */
public class EgcPassTeaserEvent extends ApplicationEvent {

    private final Long telegramId;
    private final int completedQuests;

    public EgcPassTeaserEvent(Object source, Long telegramId, int completedQuests) {
        super(source);
        this.telegramId = telegramId;
        this.completedQuests = completedQuests;
    }

    public Long getTelegramId() { return telegramId; }
    public int getCompletedQuests() { return completedQuests; }
}
