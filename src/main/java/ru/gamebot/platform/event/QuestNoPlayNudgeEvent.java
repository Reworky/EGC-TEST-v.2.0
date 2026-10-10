package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Игрок взял Brawl-квест на N боёв и спустя несколько часов не сыграл ни одного боя (2026-10-10). minutesLeft < 0 - срок не ограничен. */
public class QuestNoPlayNudgeEvent extends ApplicationEvent {

    private final Long telegramId;
    private final String questTitle;
    private final int target;
    private final boolean requireVictory;
    private final long minutesLeft;

    public QuestNoPlayNudgeEvent(Object source, Long telegramId, String questTitle, int target, boolean requireVictory, long minutesLeft) {
        super(source);
        this.telegramId = telegramId;
        this.questTitle = questTitle;
        this.target = target;
        this.requireVictory = requireVictory;
        this.minutesLeft = minutesLeft;
    }

    public Long getTelegramId() { return telegramId; }
    public String getQuestTitle() { return questTitle; }
    public int getTarget() { return target; }
    public boolean isRequireVictory() { return requireVictory; }
    public long getMinutesLeft() { return minutesLeft; }
}
