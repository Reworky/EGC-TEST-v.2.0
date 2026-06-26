package ru.gamebot.platform.domain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.Quest;

public interface QuestRepository extends JpaRepository<Quest, Long> {

    List<Quest> findAllByActiveTrueOrderByCreatedAtDesc();

    List<Quest> findAllByActiveTrueAndCategoryIgnoreCaseOrderByCreatedAtDesc(String category);

    boolean existsByTitleAndGameName(String title, String gameName);

    List<Quest> findAllByGameNameIgnoreCase(String gameName);

    void deleteAllByGameNameIgnoreCase(String gameName);
}
