package ru.gamebot.platform.event;

import java.util.List;
import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Squad;

/** Реферал вступил в отряд своего пригласившего в течение окна "за счёт приглашения" (см.
 *  SquadService.awardReferralSquadBonus) — отряду начислены бонусные очки к недельному рейтингу,
 *  участников нужно уведомить (см. GamePlatformBot.onSquadReferralBonus). */
public class SquadReferralBonusEvent extends ApplicationEvent {

    private final Squad squad;
    private final List<AppUser> members;
    private final AppUser invitedUser;
    private final long bonusPoints;

    public SquadReferralBonusEvent(Object source, Squad squad, List<AppUser> members, AppUser invitedUser, long bonusPoints) {
        super(source);
        this.squad = squad;
        this.members = members;
        this.invitedUser = invitedUser;
        this.bonusPoints = bonusPoints;
    }

    public Squad getSquad() { return squad; }
    public List<AppUser> getMembers() { return members; }
    public AppUser getInvitedUser() { return invitedUser; }
    public long getBonusPoints() { return bonusPoints; }
}
