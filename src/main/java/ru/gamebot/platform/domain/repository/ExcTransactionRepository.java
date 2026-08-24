package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.ExcTransaction;

public interface ExcTransactionRepository extends JpaRepository<ExcTransaction, Long> {

    List<ExcTransaction> findByUserOrderByCreatedAtDesc(AppUser user, Pageable pageable);

    long countByUser(AppUser user);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM ExcTransaction t WHERE t.amount > 0 AND t.createdAt >= :since")
    long sumEarnedSince(@Param("since") LocalDateTime since);
}
