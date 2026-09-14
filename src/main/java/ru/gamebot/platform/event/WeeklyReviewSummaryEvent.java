package ru.gamebot.platform.event;

import java.util.List;
import org.springframework.context.ApplicationEvent;

/** Кандидат на обобщённый недельный отчёт по отзывам для репоста в основной канал — заменяет
 *  репост одного отдельного отзыва (см. WeeklyResetScheduler.postWeeklyReviewSummary, 2026-09-14).
 *  Несёт только ID отзывов за неделю — сама сводка (текст) строится в GamePlatformBot, где есть
 *  доступ к репозиторию в рамках Hibernate-сессии. */
public class WeeklyReviewSummaryEvent extends ApplicationEvent {

    private final List<Long> reviewIds;

    public WeeklyReviewSummaryEvent(Object source, List<Long> reviewIds) {
        super(source);
        this.reviewIds = reviewIds;
    }

    public List<Long> getReviewIds() { return reviewIds; }
}
