package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

public class QuestDeadlineWarningEvent extends ApplicationEvent {

    private final Long telegramId;
    private final String questTitle;
    private final long minutesLeft;
    /** Квест «подпишись на канал»: подписка проверяется автоматически, «отправить доказательство» тут не про него. */
    private final boolean channelCheck;

    public QuestDeadlineWarningEvent(Object source, Long telegramId, String questTitle, long minutesLeft) {
        this(source, telegramId, questTitle, minutesLeft, false);
    }

    public QuestDeadlineWarningEvent(Object source, Long telegramId, String questTitle, long minutesLeft, boolean channelCheck) {
        super(source);
        this.telegramId = telegramId;
        this.questTitle = questTitle;
        this.minutesLeft = minutesLeft;
        this.channelCheck = channelCheck;
    }

    public Long getTelegramId() { return telegramId; }
    public String getQuestTitle() { return questTitle; }
    public long getMinutesLeft() { return minutesLeft; }
    public boolean isChannelCheck() { return channelCheck; }
}
