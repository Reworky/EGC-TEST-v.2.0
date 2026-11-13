package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.model.ExcWheelSpin;

public interface ExcWheelSpinRepository extends JpaRepository<ExcWheelSpin, Long> {

    List<ExcWheelSpin> findTop20ByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    long countByUserId(Long userId);

    @Query("SELECT COALESCE(SUM(s.cost), 0) FROM ExcWheelSpin s WHERE s.userId = :userId")
    long sumCostByUser(@Param("userId") Long userId);

    @Query("SELECT COALESCE(SUM(s.cost), 0) FROM ExcWheelSpin s WHERE s.createdAt >= :since")
    long sumCostSince(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(DISTINCT s.userId) FROM ExcWheelSpin s WHERE s.createdAt >= :since")
    long countPlayersSince(@Param("since") LocalDateTime since);

    @Query("SELECT s.prizeType, COUNT(s) FROM ExcWheelSpin s WHERE s.createdAt >= :since GROUP BY s.prizeType")
    List<Object[]> countByPrizeSince(@Param("since") LocalDateTime since);

    @Query("SELECT s.userId, COUNT(s) FROM ExcWheelSpin s WHERE s.createdAt >= :since AND s.cost > 0 GROUP BY s.userId ORDER BY COUNT(s) DESC")
    List<Object[]> topPlayersSince(@Param("since") LocalDateTime since, org.springframework.data.domain.Pageable pageable);
}
