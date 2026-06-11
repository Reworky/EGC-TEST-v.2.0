package ru.gamebot.platform.domain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.RewardItem;

public interface RewardItemRepository extends JpaRepository<RewardItem, Long> {

    List<RewardItem> findAllByActiveTrueOrderByPriceCoinsAsc();
}
