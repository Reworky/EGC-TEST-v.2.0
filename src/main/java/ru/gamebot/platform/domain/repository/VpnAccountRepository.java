package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.VpnAccount;

public interface VpnAccountRepository extends JpaRepository<VpnAccount, Long> {

    Optional<VpnAccount> findByTelegramId(Long telegramId);

    /** Для напоминаний о сроке: аккаунты в статусе, у которых срок попадает в окно и очередное напоминание ещё не слали. */
    List<VpnAccount> findByStatusAndReminderStageLessThanAndExpiresAtBetween(String status, int stage, LocalDateTime from, LocalDateTime to);
}
