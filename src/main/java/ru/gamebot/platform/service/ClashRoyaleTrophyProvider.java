package ru.gamebot.platform.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.gamebot.platform.domain.model.Tournament;

/** Clash Royale: поле trophies из players/{tag} - кубки Trophy Road (тот же токен CLASH_ROYALE_API_TOKEN, что у квестов).
 *  Рейтинг «Путь легенд» - отдельные поля ответа (currentPathOfLegendSeasonResult), в подсчёт не входит. */
@Component
@RequiredArgsConstructor
public class ClashRoyaleTrophyProvider implements TrophyGameProvider {

    private final ClashRoyaleApiService clashRoyaleApiService;

    @Override
    public Tournament.ScoringType scoringType() {
        return Tournament.ScoringType.CLASH_ROYALE_TROPHIES;
    }

    @Override
    public String gameName() {
        return "Clash Royale";
    }

    @Override
    public boolean isEnabled() {
        return clashRoyaleApiService.isEnabled();
    }

    @Override
    public Optional<TrophyPlayer> fetchPlayer(String rawTag) throws TrophyApiTransientException {
        try {
            return clashRoyaleApiService.fetchPlayer(rawTag).map(p -> new TrophyPlayer(p.tag(), p.name(), p.trophies()));
        } catch (ClashRoyaleApiService.ClashRoyaleApiTransientException e) {
            throw new TrophyApiTransientException(e.getMessage(), e);
        }
    }
}
