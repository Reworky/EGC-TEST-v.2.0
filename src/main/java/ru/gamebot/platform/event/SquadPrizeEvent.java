package ru.gamebot.platform.event;

import java.util.List;
import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Squad;

public class SquadPrizeEvent extends ApplicationEvent {

    private final Squad squad;
    private final List<AppUser> members;
    private final long prizePerMember;
    private final long totalWeeklyXp;

    public SquadPrizeEvent(Object source, Squad squad, List<AppUser> members, long prizePerMember, long totalWeeklyXp) {
        super(source);
        this.squad = squad;
        this.members = members;
        this.prizePerMember = prizePerMember;
        this.totalWeeklyXp = totalWeeklyXp;
    }

    public Squad getSquad() { return squad; }
    public List<AppUser> getMembers() { return members; }
    public long getPrizePerMember() { return prizePerMember; }
    public long getTotalWeeklyXp() { return totalWeeklyXp; }
}
