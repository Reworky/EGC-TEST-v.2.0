package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Игрок активен в боте/мини-аппе (заходил недавно), но давно не брал квест — не путать с общей
 *  спячкой (DormancyReengagementEvent, по ОБЩЕЙ неактивности) и онбордингом (для тех, кто вообще
 *  не брал первый квест). См. WeeklyResetScheduler.checkQuestGapNudge(). */
public class QuestGapNudgeEvent extends ApplicationEvent {

    private final Long telegramId;
    private final long daysSinceLastQuest;

    public QuestGapNudgeEvent(Object source, Long telegramId, long daysSinceLastQuest) {
        super(source);
        this.telegramId = telegramId;
        this.daysSinceLastQuest = daysSinceLastQuest;
    }

    public Long getTelegramId() { return telegramId; }
    public long getDaysSinceLastQuest() { return daysSinceLastQuest; }
}
