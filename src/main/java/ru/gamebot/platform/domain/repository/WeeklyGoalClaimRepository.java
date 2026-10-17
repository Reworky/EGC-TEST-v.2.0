package ru.gamebot.platform.domain.repository;

import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.WeeklyGoalClaim;

public interface WeeklyGoalClaimRepository extends JpaRepository<WeeklyGoalClaim, Long> {

    boolean existsByUserIdAndWeekStart(Long userId, LocalDate weekStart);

    long countByWeekStart(LocalDate weekStart);
}
