package ru.gamebot.platform.event;

import java.util.List;
import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.domain.model.AppUser;

public class HallOfFameEvent extends ApplicationEvent {

    public record HallEntry(int rank, String nickname, String username, long weeklyXp, long totalXp) {}

    private final List<HallEntry> top3;

    public HallOfFameEvent(Object source, List<HallEntry> top3) {
        super(source);
        this.top3 = top3;
    }

    public List<HallEntry> getTop3() { return top3; }

    public static HallEntry fromUser(int rank, AppUser user) {
        return new HallEntry(rank, user.getNickname(), user.getTelegramUsername(), user.getWeeklyXp(), user.getXp());
    }
}
