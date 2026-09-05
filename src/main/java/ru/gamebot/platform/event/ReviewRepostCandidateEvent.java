package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Раз в неделю найден лучший ещё не предлагавшийся отзыв (см. WeeklyResetScheduler) —
 * администратору предлагается репостнуть его из @egc_payouts в основной канал. */
public class ReviewRepostCandidateEvent extends ApplicationEvent {

    private final Long reviewId;

    public ReviewRepostCandidateEvent(Object source, Long reviewId) {
        super(source);
        this.reviewId = reviewId;
    }

    public Long getReviewId() { return reviewId; }
}
