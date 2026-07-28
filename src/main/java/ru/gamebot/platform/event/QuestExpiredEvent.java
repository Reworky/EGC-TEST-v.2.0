package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

public class QuestExpiredEvent extends ApplicationEvent {

    private final Long telegramId;
    private final String questTitle;

    public QuestExpiredEvent(Object source, Long telegramId, String questTitle) {
        super(source);
        this.telegramId = telegramId;
        this.questTitle = questTitle;
    }

    public Long getTelegramId() { return telegramId; }
    public String getQuestTitle() { return questTitle; }
}
