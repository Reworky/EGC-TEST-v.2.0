package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.model.EgcPassEvent;

public interface EgcPassEventRepository extends JpaRepository<EgcPassEvent, Long> {

    @Query("SELECT COUNT(e) FROM EgcPassEvent e WHERE e.type = :type AND e.createdAt >= :from AND e.createdAt < :to")
    long countByType(@Param("type") String type, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(DISTINCT e.telegramId) FROM EgcPassEvent e WHERE e.type = :type AND e.createdAt >= :from AND e.createdAt < :to")
    long countUsersByType(@Param("type") String type, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Показы предложения по источникам: [источник, число показов, уникальных игроков]. */
    @Query("SELECT e.source, COUNT(e), COUNT(DISTINCT e.telegramId) FROM EgcPassEvent e WHERE e.type = 'OFFER_SENT' AND e.createdAt >= :from AND e.createdAt < :to GROUP BY e.source")
    List<Object[]> offersBySource(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
