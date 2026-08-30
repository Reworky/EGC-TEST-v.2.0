package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.enums.ScheduledBroadcastStatus;
import ru.gamebot.platform.domain.model.ScheduledBroadcast;

public interface ScheduledBroadcastRepository extends JpaRepository<ScheduledBroadcast, Long> {

    List<ScheduledBroadcast> findByStatusAndScheduledAtBefore(ScheduledBroadcastStatus status, LocalDateTime cutoff);

    List<ScheduledBroadcast> findByStatusOrderByScheduledAtAsc(ScheduledBroadcastStatus status);
}
