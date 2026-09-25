package ru.gamebot.platform.domain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.AdPriceItem;

public interface AdPriceItemRepository extends JpaRepository<AdPriceItem, Long> {

    List<AdPriceItem> findAllByOrderByIdAsc();
}
