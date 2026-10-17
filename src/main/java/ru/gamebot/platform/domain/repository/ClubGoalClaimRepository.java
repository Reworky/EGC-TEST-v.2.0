package ru.gamebot.platform.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.ClubGoalClaim;

public interface ClubGoalClaimRepository extends JpaRepository<ClubGoalClaim, Long> {

    boolean existsByGoalIdAndUserId(Long goalId, Long userId);

    long countByGoalId(Long goalId);
}
