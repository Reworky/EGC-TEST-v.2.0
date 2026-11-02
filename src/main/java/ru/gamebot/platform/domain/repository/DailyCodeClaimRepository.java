package ru.gamebot.platform.domain.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.model.DailyCodeClaim;

public interface DailyCodeClaimRepository extends JpaRepository<DailyCodeClaim, Long> {

    boolean existsByUserIdAndClaimDate(Long userId, LocalDate claimDate);

    boolean existsByUserIdAndCodeId(Long userId, Long codeId);

    List<DailyCodeClaim> findTop30ByUserIdOrderByClaimDateDesc(Long userId);

    long countByCodeId(Long codeId);

    long countByUserIdAndClaimDateBetween(Long userId, LocalDate from, LocalDate to);

    /** Игроки, поймавшие за период не меньше min кодов: строки [userId, количество]. */
    @Query("SELECT c.userId, COUNT(c) FROM DailyCodeClaim c WHERE c.claimDate >= :from AND c.claimDate <= :to GROUP BY c.userId HAVING COUNT(c) >= :min")
    List<Object[]> findUsersWithClaimsInRange(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("min") long min);
}
