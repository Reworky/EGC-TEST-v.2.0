package ru.gamebot.platform.event;

import java.util.List;
import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.service.UserService;

public class ReferralLeaderboardRewardEvent extends ApplicationEvent {

    private final List<UserService.ReferralRankEntry> winners;

    public ReferralLeaderboardRewardEvent(Object source, List<UserService.ReferralRankEntry> winners) {
        super(source);
        this.winners = winners;
    }

    public List<UserService.ReferralRankEntry> getWinners() { return winners; }
}
