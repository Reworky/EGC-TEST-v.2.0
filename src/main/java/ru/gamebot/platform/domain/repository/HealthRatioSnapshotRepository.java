package ru.gamebot.platform.domain.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.HealthRatioSnapshot;

public interface HealthRatioSnapshotRepository extends JpaRepository<HealthRatioSnapshot, Long> {

    Optional<HealthRatioSnapshot> findTopByOrderByCalculatedAtDesc();
}
