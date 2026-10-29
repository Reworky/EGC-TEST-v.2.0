package ru.gamebot.platform.domain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.PulseThresholdLog;

public interface PulseThresholdLogRepository extends JpaRepository<PulseThresholdLog, Long> {

    List<PulseThresholdLog> findTop20ByOrderByChangedAtDesc();
}
