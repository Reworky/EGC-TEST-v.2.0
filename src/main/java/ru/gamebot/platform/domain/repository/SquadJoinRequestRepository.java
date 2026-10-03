package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.SquadJoinRequest;

public interface SquadJoinRequestRepository extends JpaRepository<SquadJoinRequest, Long> {

    List<SquadJoinRequest> findAllBySquadIdAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(Long squadId, String status, LocalDateTime after);

    List<SquadJoinRequest> findAllByUserIdAndStatus(Long userId, String status);

    boolean existsBySquadIdAndUserIdAndStatus(Long squadId, Long userId, String status);

    long countBySquadIdAndStatusAndCreatedAtAfter(Long squadId, String status, LocalDateTime after);
}
