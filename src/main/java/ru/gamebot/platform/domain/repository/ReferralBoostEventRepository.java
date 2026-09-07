package ru.gamebot.platform.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.ReferralBoostEvent;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReferralBoostEventRepository extends JpaRepository<ReferralBoostEvent, Long> {
    List<ReferralBoostEvent> findAllByOrderByCreatedAtDesc();
    Optional<ReferralBoostEvent> findFirstByActiveTrueAndStartAtBeforeAndEndAtAfterOrderByCreatedAtDesc(
            LocalDateTime now1, LocalDateTime now2);
}
