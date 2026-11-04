package ru.gamebot.platform.domain.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.Vpn2ManualPayment;

public interface Vpn2ManualPaymentRepository extends JpaRepository<Vpn2ManualPayment, Long> {

    /** Последняя заявка человека в нужном статусе (например, ждёт чек или ждёт админа). */
    Optional<Vpn2ManualPayment> findFirstByTelegramIdAndStatusOrderByCreatedAtDesc(Long telegramId, String status);

    long countByStatus(String status);
}
