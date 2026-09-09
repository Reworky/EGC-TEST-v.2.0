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

    boolean existsByUserAndDescription(AppUser user, String description);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM ExcTransaction t WHERE t.amount > 0 AND t.createdAt >= :since")
    long sumEarnedSince(@Param("since") LocalDateTime since);

    /** Рейтинг рефереров по реферальному доходу за окно — [userId, sumAmount], для еженедельного топа. */
    @Query("SELECT t.user.id, SUM(t.amount) FROM ExcTransaction t "
            + "WHERE t.type = 'REFERRAL' AND t.amount > 0 AND t.createdAt >= :from AND t.createdAt < :to "
            + "GROUP BY t.user.id ORDER BY SUM(t.amount) DESC")
    List<Object[]> findReferralEarningsRankingBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    // ── Экономика рефералки (2026-09-09): тип REFERRAL смешивает два разных начисления рефереру —
    // разовый бонус "+300 EXC за приглашение" (description "Реферальный бонус за приглашение: ...")
    // и еженедельный ручеёк "10% с квеста реферала" (description "N% с квеста реферала ..."). Для
    // сравнения кандидата на разовый бонус с РЕАЛЬНЫМ ручейком нужен только второй — иначе метрика
    // завышена почти вдвое за счёт инстант-бонуса, который в любом случае остаётся неизменным.
    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM ExcTransaction t "
            + "WHERE t.type = 'REFERRAL' AND t.description LIKE '%с квеста реферала%'")
    long sumReferralTrickleOnly();

    @Query("SELECT COUNT(DISTINCT t.user.id) FROM ExcTransaction t "
            + "WHERE t.type = 'REFERRAL' AND t.description LIKE '%с квеста реферала%'")
    long countReferrersWithTrickleEarnings();
}
