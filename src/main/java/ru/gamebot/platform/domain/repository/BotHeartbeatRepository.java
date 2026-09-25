package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.BotHeartbeat;

public interface BotHeartbeatRepository extends JpaRepository<BotHeartbeat, Long> {

    long countByTsGreaterThanEqualAndTsLessThan(LocalDateTime from, LocalDateTime to);

    Optional<BotHeartbeat> findFirstByOrderByTsAsc();

    @Modifying
    @Transactional
    @Query("DELETE FROM BotHeartbeat h WHERE h.ts < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
