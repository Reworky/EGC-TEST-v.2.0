package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.model.Quest;

public interface QuestRepository extends JpaRepository<Quest, Long> {

    List<Quest> findAllByActiveTrueOrderByCreatedAtDesc();

    long countByActiveTrue();

    List<Quest> findAllByActiveTrueAndCategoryIgnoreCaseOrderByCreatedAtDesc(String category);

    boolean existsByTitleAndGameName(String title, String gameName);

    java.util.Optional<Quest> findFirstByTitleAndGameName(String title, String gameName);

    List<Quest> findAllByGameNameIgnoreCase(String gameName);

    long countByGameNameIgnoreCaseAndActiveTrue(String gameName);

    void deleteAllByGameNameIgnoreCase(String gameName);

    @Query("SELECT COUNT(q) FROM Quest q WHERE q.createdAt >= :from AND q.createdAt < :to")
    long countCreatedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
