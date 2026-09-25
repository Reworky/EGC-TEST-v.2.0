package ru.gamebot.platform.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.gamebot.platform.domain.model.Tournament;

@Component
@RequiredArgsConstructor
public class BrawlStarsTrophyProvider implements TrophyGameProvider {

    private final BrawlStarsApiService brawlStarsApiService;

    @Override
    public Tournament.ScoringType scoringType() {
        return Tournament.ScoringType.BRAWL_TROPHIES;
    }

    @Override
    public String gameName() {
        return "Brawl Stars";
    }

    @Override
    public boolean isEnabled() {
        return brawlStarsApiService.isEnabled();
    }

    @Override
    public Optional<TrophyPlayer> fetchPlayer(String rawTag) throws TrophyApiTransientException {
        try {
            return brawlStarsApiService.fetchPlayer(rawTag).map(p -> new TrophyPlayer(p.tag(), p.name(), p.trophies()));
        } catch (BrawlStarsApiService.BrawlStarsTransientException e) {
            throw new TrophyApiTransientException(e.getMessage(), e);
        }
    }
}
