package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.model.PartnerQuestEvent;

public interface PartnerQuestEventRepository extends JpaRepository<PartnerQuestEvent, Long> {

    @Query("SELECT COUNT(DISTINCT e.telegramId) FROM PartnerQuestEvent e WHERE e.type = :type AND e.createdAt >= :from AND e.createdAt < :to")
    long countUsersByType(@Param("type") String type, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
