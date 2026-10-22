package ru.gamebot.platform.event;

import java.util.List;
import org.springframework.context.ApplicationEvent;

/** Заявки по спонсорским квестам с ручной проверкой ждут модерации дольше SponsorModerationReminderService.WAIT_HOURS часов: админам и модераторам уходит напоминание. */
public class SponsorModerationOverdueEvent extends ApplicationEvent {

    private final List<Long> submissionIds;

    public SponsorModerationOverdueEvent(Object source, List<Long> submissionIds) {
        super(source);
        this.submissionIds = submissionIds;
    }

    public List<Long> getSubmissionIds() { return submissionIds; }
}
