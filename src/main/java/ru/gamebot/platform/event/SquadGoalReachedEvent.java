package ru.gamebot.platform.event;

import java.util.List;
import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Squad;

/** Отряд выполнил командную цель недели, участникам с вкладом начислена награда (SquadService.settleWeeklyGoals) —
 *  их нужно уведомить (GamePlatformBot.onSquadGoalReached). */
public class SquadGoalReachedEvent extends ApplicationEvent {

    private final Squad squad;
    private final List<AppUser> rewardedMembers;
    private final long bonusPerMember;
    private final long done;
    private final long target;

    public SquadGoalReachedEvent(Object source, Squad squad, List<AppUser> rewardedMembers, long bonusPerMember, long done, long target) {
        super(source);
        this.squad = squad;
        this.rewardedMembers = rewardedMembers;
        this.bonusPerMember = bonusPerMember;
        this.done = done;
        this.target = target;
    }

    public Squad getSquad() { return squad; }
    public List<AppUser> getRewardedMembers() { return rewardedMembers; }
    public long getBonusPerMember() { return bonusPerMember; }
    public long getDone() { return done; }
    public long getTarget() { return target; }
}
