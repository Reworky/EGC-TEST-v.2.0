package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Прогресс автоквеста дошёл до ступени: level 1 - половина пути, level 2 - последний шаг (2026-10-10). */
public class QuestProgressMilestoneEvent extends ApplicationEvent {

    private final Long telegramId;
    private final String questTitle;
    private final long progress;
    private final long target;
    private final int level;

    public QuestProgressMilestoneEvent(Object source, Long telegramId, String questTitle, long progress, long target, int level) {
        super(source);
        this.telegramId = telegramId;
        this.questTitle = questTitle;
        this.progress = progress;
        this.target = target;
        this.level = level;
    }

    public Long getTelegramId() { return telegramId; }
    public String getQuestTitle() { return questTitle; }
    public long getProgress() { return progress; }
    public long getTarget() { return target; }
    public int getLevel() { return level; }
}
