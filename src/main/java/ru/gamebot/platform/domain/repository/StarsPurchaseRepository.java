package ru.gamebot.platform.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.StarsPurchase;

public interface StarsPurchaseRepository extends JpaRepository<StarsPurchase, Long> {
}
