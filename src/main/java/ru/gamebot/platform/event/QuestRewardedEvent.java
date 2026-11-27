package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Квест игрока одобрен и награда начислена (любой путь одобрения). */
public class QuestRewardedEvent extends ApplicationEvent {

    private final Long userId;
    private final Long submissionId;

    public QuestRewardedEvent(Object source, Long userId, Long submissionId) {
        super(source);
        this.userId = userId;
        this.submissionId = submissionId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getSubmissionId() {
        return submissionId;
    }
}
