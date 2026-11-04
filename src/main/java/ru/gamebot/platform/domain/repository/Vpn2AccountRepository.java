package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.Vpn2Account;

public interface Vpn2AccountRepository extends JpaRepository<Vpn2Account, Long> {

    Optional<Vpn2Account> findByTelegramId(Long telegramId);

    /** Для напоминаний о сроке: аккаунты в статусе, у которых срок попадает в окно и очередное напоминание ещё не слали. */
    List<Vpn2Account> findByStatusAndReminderStageLessThanAndExpiresAtBetween(String status, int stage, LocalDateTime from, LocalDateTime to);

    long countByStatusAndPlanTypeAndExpiresAtAfter(String status, String planType, LocalDateTime after);

    long countByStatusAndExpiresAtBetween(String status, LocalDateTime from, LocalDateTime to);

    long countByTrialUsedAtIsNotNull();
}
