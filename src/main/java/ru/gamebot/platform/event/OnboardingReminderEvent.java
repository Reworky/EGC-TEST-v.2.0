package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

public class OnboardingReminderEvent extends ApplicationEvent {

    private final Long telegramId;
    private final int notificationNumber; // 1, 2 or 3

    public OnboardingReminderEvent(Object source, Long telegramId, int notificationNumber) {
        super(source);
        this.telegramId = telegramId;
        this.notificationNumber = notificationNumber;
    }

    public Long getTelegramId() { return telegramId; }
    public int getNotificationNumber() { return notificationNumber; }
}
