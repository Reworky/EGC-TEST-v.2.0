package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Пора предложить игроку ответить на вопросы про возраст и страну за награду (10.11.2026). reminder=false - сразу после первого одобренного квеста,
 *  reminder=true - единственное напоминание через двое суток, если игрок не ответил. */
public class AboutPromptEvent extends ApplicationEvent {

    private final Long telegramId;
    private final boolean reminder;

    public AboutPromptEvent(Object source, Long telegramId, boolean reminder) {
        super(source);
        this.telegramId = telegramId;
        this.reminder = reminder;
    }

    public Long getTelegramId() { return telegramId; }
    public boolean isReminder() { return reminder; }
}
