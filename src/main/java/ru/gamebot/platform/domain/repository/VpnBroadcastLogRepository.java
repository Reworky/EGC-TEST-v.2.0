package ru.gamebot.platform.domain.repository;

import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.gamebot.platform.domain.model.VpnBroadcastLog;

public interface VpnBroadcastLogRepository extends JpaRepository<VpnBroadcastLog, Long> {

    /** Кому рассылка про VPN уже дошла: повторно им не шлём. */
    @Query("select distinct l.telegramId from VpnBroadcastLog l where l.delivered = true")
    Set<Long> findDeliveredTelegramIds();
}
