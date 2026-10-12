package ru.gamebot.platform.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.model.SponsorQuestEvent;

import java.time.LocalDateTime;
import java.util.List;

public interface SponsorQuestEventRepository extends JpaRepository<SponsorQuestEvent, Long> {

    boolean existsByQuestIdAndUserIdAndKind(Long questId, Long userId, String kind);

    boolean existsByQuestIdAndUserIdAndKindAndCreatedAtAfter(Long questId, Long userId, String kind, LocalDateTime since);

    @Query("SELECT COUNT(DISTINCT e.userId) FROM SponsorQuestEvent e WHERE e.questId = :questId AND e.kind = :kind")
    long countDistinctUsers(@Param("questId") Long questId, @Param("kind") String kind);

    /** Игроки, которым пуш при создании ушёл в окне [from, to] (для напоминания через сутки). */
    @Query("SELECT DISTINCT e.userId FROM SponsorQuestEvent e WHERE e.questId = :questId AND e.kind = :kind "
            + "AND e.createdAt >= :from AND e.createdAt <= :to")
    List<Long> findUserIdsInWindow(@Param("questId") Long questId, @Param("kind") String kind,
                                   @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
