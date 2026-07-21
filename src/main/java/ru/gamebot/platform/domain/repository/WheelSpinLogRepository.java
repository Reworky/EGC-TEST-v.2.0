package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.WheelSpinLog;

public interface WheelSpinLogRepository extends JpaRepository<WheelSpinLog, Long> {

    @Query("SELECT COUNT(w) FROM WheelSpinLog w WHERE w.user = :user AND w.createdAt >= :since")
    long countByUserSince(@Param("user") AppUser user, @Param("since") LocalDateTime since);
}
