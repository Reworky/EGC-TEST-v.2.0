package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;

/** Срок удержания части награды за подписку на канал спонсора вышел: бот проверяет, остался ли игрок в канале, и выплачивает или снимает удержание. */
public class HeldChannelRewardDueEvent extends ApplicationEvent {

    private final Long submissionId;

    public HeldChannelRewardDueEvent(Object source, Long submissionId) {
        super(source);
        this.submissionId = submissionId;
    }

    public Long getSubmissionId() { return submissionId; }
}
