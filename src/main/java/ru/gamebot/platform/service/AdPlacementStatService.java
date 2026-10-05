package ru.gamebot.platform.service;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AdPlacementStat;
import ru.gamebot.platform.domain.repository.AdPlacementStatRepository;

/** Счётчики рекламы за награду по местам показа. Сбой записи статистики никогда не должен мешать показу рекламы или начислению награды. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdPlacementStatService {

    private final AdPlacementStatRepository repository;

    public void recordRequest(String placement) {
        record(placement, true);
    }

    public void recordReward(String placement) {
        record(placement, false);
    }

    private synchronized void record(String placement, boolean request) {
        try {
            String place = placement == null || placement.isBlank() ? "other" : placement.trim();
            if (place.length() > 24) place = place.substring(0, 24);
            LocalDate today = LocalDate.now();
            String id = today + "|" + place;
            final String placeFinal = place;
            AdPlacementStat stat = repository.findById(id).orElseGet(() -> {
                AdPlacementStat created = new AdPlacementStat();
                created.setId(id);
                created.setDay(today);
                created.setPlacement(placeFinal);
                return created;
            });
            if (request) stat.setRequests(stat.getRequests() + 1);
            else stat.setRewards(stat.getRewards() + 1);
            repository.save(stat);
        } catch (Exception e) {
            log.warn("Failed to record ad placement stat", e);
        }
    }
}
