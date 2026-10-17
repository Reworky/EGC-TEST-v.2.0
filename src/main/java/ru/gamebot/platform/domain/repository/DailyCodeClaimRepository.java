package ru.gamebot.platform.domain.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.DailyCodeClaim;

public interface DailyCodeClaimRepository extends JpaRepository<DailyCodeClaim, Long> {

    boolean existsByUserIdAndClaimDate(Long userId, LocalDate claimDate);

    boolean existsByUserIdAndCodeId(Long userId, Long codeId);

    List<DailyCodeClaim> findTop30ByUserIdOrderByClaimDateDesc(Long userId);

    long countByCodeId(Long codeId);
}
