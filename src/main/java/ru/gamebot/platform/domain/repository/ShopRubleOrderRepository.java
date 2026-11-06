package ru.gamebot.platform.domain.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.ShopRubleOrder;

public interface ShopRubleOrderRepository extends JpaRepository<ShopRubleOrder, Long> {

    Optional<ShopRubleOrder> findFirstByTelegramIdAndStatusOrderByCreatedAtDesc(Long telegramId, String status);
}
