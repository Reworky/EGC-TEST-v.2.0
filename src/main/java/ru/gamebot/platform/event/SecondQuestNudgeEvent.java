package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Точечное напоминание про второй квест — игрок выполнил ровно один квест, прошло 2-3 дня, второго
 *  так и нет (см. WeeklyResetScheduler.checkSecondQuestNudge). Разовое, с небольшим бонусом EXC за
 *  возврат именно сейчас — отдельная от общей "спячки" (DormancyReengagementEvent, 14/30/60 дней
 *  общей неактивности) ниша: конкретно критический момент "первый квест был, а вошёл ли во вкус?"
 *  (аудит вовлечённости, 2026-09-14). */
public class SecondQuestNudgeEvent extends ApplicationEvent {

    private final Long telegramId;
    private final long excGranted;

    public SecondQuestNudgeEvent(Object source, Long telegramId, long excGranted) {
        super(source);
        this.telegramId = telegramId;
        this.excGranted = excGranted;
    }

    public Long getTelegramId() { return telegramId; }
    public long getExcGranted() { return excGranted; }
}
