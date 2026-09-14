package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.QuestRewardBoostEvent;

public interface QuestRewardBoostEventRepository extends JpaRepository<QuestRewardBoostEvent, Long> {
    List<QuestRewardBoostEvent> findAllByOrderByCreatedAtDesc();

    Optional<QuestRewardBoostEvent> findFirstByActiveTrueAndStartAtBeforeAndEndAtAfterOrderByCreatedAtDesc(
            LocalDateTime now1, LocalDateTime now2);
}
