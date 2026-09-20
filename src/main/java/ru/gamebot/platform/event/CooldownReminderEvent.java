package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Повторное напоминание (2026-09-20) — игрок получил CooldownExpiredEvent, но за
 *  COOLDOWN_REMINDER_DELAY_HOURS после этого так и не взял квест заново. Отправляется не более
 *  одного раза на заявку (см. QuestSubmission.cooldownReminderSentAt /
 *  WeeklyResetScheduler.notifyCooldownReminderIfIgnored). */
public class CooldownReminderEvent extends ApplicationEvent {

    private final Long telegramId;
    private final String gameName;
    private final String questTitle;

    public CooldownReminderEvent(Object source, Long telegramId, String gameName, String questTitle) {
        super(source);
        this.telegramId = telegramId;
        this.gameName = gameName;
        this.questTitle = questTitle;
    }

    public Long getTelegramId() { return telegramId; }
    public String getGameName() { return gameName; }
    public String getQuestTitle() { return questTitle; }
}
