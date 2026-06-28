package ru.gamebot.platform.domain.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.GameCatalog;

public interface GameCatalogRepository extends JpaRepository<GameCatalog, Long> {
    Optional<GameCatalog> findByGameNameIgnoreCase(String gameName);
}
