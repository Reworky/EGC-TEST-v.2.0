package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

public class QuestReportSubmittedEvent extends ApplicationEvent {

    private final Long submissionId;

    public QuestReportSubmittedEvent(Object source, Long submissionId) {
        super(source);
        this.submissionId = submissionId;
    }

    public Long getSubmissionId() { return submissionId; }
}
