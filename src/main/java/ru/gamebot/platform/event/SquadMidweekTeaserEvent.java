package ru.gamebot.platform.event;

import java.util.List;
import org.springframework.context.ApplicationEvent;
import ru.gamebot.platform.service.SquadService;

public class SquadMidweekTeaserEvent extends ApplicationEvent {

    private final List<SquadService.SquadRankEntry> topSquads;

    public SquadMidweekTeaserEvent(Object source, List<SquadService.SquadRankEntry> topSquads) {
        super(source);
        this.topSquads = topSquads;
    }

    public List<SquadService.SquadRankEntry> getTopSquads() { return topSquads; }
}
