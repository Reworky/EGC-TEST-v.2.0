package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.QuestPackSwitchLog;

public interface QuestPackSwitchLogRepository extends JpaRepository<QuestPackSwitchLog, Long> {

    List<QuestPackSwitchLog> findTop10ByOrderBySwitchedAtDesc();

    long countBySwitchedAtAfter(LocalDateTime since);
}
