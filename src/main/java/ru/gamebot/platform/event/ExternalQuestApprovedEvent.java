package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

public class ExternalQuestApprovedEvent extends ApplicationEvent {

    private final Long submissionId;

    public ExternalQuestApprovedEvent(Object source, Long submissionId) {
        super(source);
        this.submissionId = submissionId;
    }

    public Long getSubmissionId() { return submissionId; }
}
