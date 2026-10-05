package ru.gamebot.platform.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.AdPlacementStat;

public interface AdPlacementStatRepository extends JpaRepository<AdPlacementStat, String> {
}
