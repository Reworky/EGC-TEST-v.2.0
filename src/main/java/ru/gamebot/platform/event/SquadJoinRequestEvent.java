package ru.gamebot.platform.event;

import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Squad;

/** Игрок подал заявку в отряд с закрытым набором — капитана нужно уведомить (GamePlatformBot.onSquadJoinRequest). */
public class SquadJoinRequestEvent extends ApplicationEvent {

    private final Squad squad;
    private final AppUser applicant;
    private final Long requestId;

    public SquadJoinRequestEvent(Object source, Squad squad, AppUser applicant, Long requestId) {
        super(source);
        this.squad = squad;
        this.applicant = applicant;
        this.requestId = requestId;
    }

    public Squad getSquad() { return squad; }
    public AppUser getApplicant() { return applicant; }
    public Long getRequestId() { return requestId; }
}
