package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

public class Cs2QuestAutoVerifiedEvent extends ApplicationEvent {

    private final Long submissionId;

    public Cs2QuestAutoVerifiedEvent(Object source, Long submissionId) {
        super(source);
        this.submissionId = submissionId;
    }

    public Long getSubmissionId() { return submissionId; }
}
