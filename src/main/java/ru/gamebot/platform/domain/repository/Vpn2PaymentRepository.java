package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.model.Vpn2Payment;

public interface Vpn2PaymentRepository extends JpaRepository<Vpn2Payment, Long> {

    /** Последние оплаты человека для возврата. */
    List<Vpn2Payment> findTop5ByTelegramIdOrderByCreatedAtDesc(Long telegramId);

    long countByCreatedAtAfter(LocalDateTime after);

    @Query("select coalesce(sum(p.starsAmount), 0) from Vpn2Payment p where p.createdAt >= :from")
    long sumStarsSince(@Param("from") LocalDateTime from);
}
