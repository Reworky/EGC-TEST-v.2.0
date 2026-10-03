package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Squad;

/** Капитан принял или отклонил заявку — заявителя нужно уведомить (GamePlatformBot.onSquadJoinDecision). */
public class SquadJoinDecisionEvent extends ApplicationEvent {

    private final Squad squad;
    private final AppUser applicant;
    private final boolean approved;

    public SquadJoinDecisionEvent(Object source, Squad squad, AppUser applicant, boolean approved) {
        super(source);
        this.squad = squad;
        this.applicant = applicant;
        this.approved = approved;
    }

    public Squad getSquad() { return squad; }
    public AppUser getApplicant() { return applicant; }
    public boolean isApproved() { return approved; }
}
