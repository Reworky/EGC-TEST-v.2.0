package ru.gamebot.platform.event;

import java.util.List;
import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Squad;

/** Отряд впервые достиг порогового размера (3 или 5 человек) и всем участникам начислен разовый
 *  EXC-бонус (см. SquadService.awardSizeMilestoneIfReached, ТЗ EGC_TZ_otryady, 2026-10-02) -
 *  участников нужно уведомить (см. GamePlatformBot.onSquadMilestoneReached). */
public class SquadMilestoneReachedEvent extends ApplicationEvent {

    private final Squad squad;
    private final List<AppUser> members;
    private final int size;
    private final long bonusPerMember;

    public SquadMilestoneReachedEvent(Object source, Squad squad, List<AppUser> members, int size, long bonusPerMember) {
        super(source);
        this.squad = squad;
        this.members = members;
        this.size = size;
        this.bonusPerMember = bonusPerMember;
    }

    public Squad getSquad() { return squad; }
    public List<AppUser> getMembers() { return members; }
    public int getSize() { return size; }
    public long getBonusPerMember() { return bonusPerMember; }
}
