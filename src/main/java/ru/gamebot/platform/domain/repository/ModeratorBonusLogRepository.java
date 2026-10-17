package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.model.ModeratorBonusLog;

public interface ModeratorBonusLogRepository extends JpaRepository<ModeratorBonusLog, Long> {

    @Query("SELECT COALESCE(SUM(l.exc), 0) FROM ModeratorBonusLog l WHERE l.moderatorTelegramId = :id AND l.createdAt >= :since")
    long sumExcSince(@Param("id") Long moderatorTelegramId, @Param("since") LocalDateTime since);

    @Query("SELECT COALESCE(SUM(l.xp), 0) FROM ModeratorBonusLog l WHERE l.moderatorTelegramId = :id AND l.createdAt >= :since")
    long sumXpSince(@Param("id") Long moderatorTelegramId, @Param("since") LocalDateTime since);
}
