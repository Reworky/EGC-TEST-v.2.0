package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

public class QuestDeadlineWarningEvent extends ApplicationEvent {

    private final Long telegramId;
    private final String questTitle;
    private final long minutesLeft;

    public QuestDeadlineWarningEvent(Object source, Long telegramId, String questTitle, long minutesLeft) {
        super(source);
        this.telegramId = telegramId;
        this.questTitle = questTitle;
        this.minutesLeft = minutesLeft;
    }

    public Long getTelegramId() { return telegramId; }
    public String getQuestTitle() { return questTitle; }
    public long getMinutesLeft() { return minutesLeft; }
}
