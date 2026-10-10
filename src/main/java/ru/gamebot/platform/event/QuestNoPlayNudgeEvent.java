package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Игрок взял автоквест (Brawl Stars / Clash Royale / Clash of Clans) и спустя несколько часов прогресса нет (2026-10-10). minutesLeft < 0 - срок не ограничен. */
public class QuestNoPlayNudgeEvent extends ApplicationEvent {

    private final Long telegramId;
    private final String questTitle;
    private final String gameName;
    private final String description;
    private final long minutesLeft;

    public QuestNoPlayNudgeEvent(Object source, Long telegramId, String questTitle, String gameName, String description, long minutesLeft) {
        super(source);
        this.telegramId = telegramId;
        this.questTitle = questTitle;
        this.gameName = gameName;
        this.description = description;
        this.minutesLeft = minutesLeft;
    }

    public Long getTelegramId() { return telegramId; }
    public String getQuestTitle() { return questTitle; }
    public String getGameName() { return gameName; }
    public String getDescription() { return description; }
    public long getMinutesLeft() { return minutesLeft; }
}
