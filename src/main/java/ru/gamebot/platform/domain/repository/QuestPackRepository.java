package ru.gamebot.platform.domain.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.gamebot.platform.domain.model.QuestPack;

public interface QuestPackRepository extends JpaRepository<QuestPack, Long> {

    List<QuestPack> findAllByGameNameIgnoreCaseOrderByIdAsc(String gameName);

    Optional<QuestPack> findFirstByGameNameIgnoreCaseAndActiveTrue(String gameName);

    Optional<QuestPack> findFirstByGameNameIgnoreCaseAndSeederManagedTrue(String gameName);

    @Query("SELECT DISTINCT p.gameName FROM QuestPack p")
    List<String> findAllGameNames();
}
