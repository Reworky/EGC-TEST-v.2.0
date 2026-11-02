package ru.gamebot.platform.domain.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.VpnAccount;

public interface VpnAccountRepository extends JpaRepository<VpnAccount, Long> {

    Optional<VpnAccount> findByTelegramId(Long telegramId);
}
