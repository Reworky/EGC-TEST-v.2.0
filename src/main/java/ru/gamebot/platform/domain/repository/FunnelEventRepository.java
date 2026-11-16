package ru.gamebot.platform.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.FunnelEvent;

public interface FunnelEventRepository extends JpaRepository<FunnelEvent, Long> {

    boolean existsByTelegramIdAndStep(Long telegramId, String step);
}
