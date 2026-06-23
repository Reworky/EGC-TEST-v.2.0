package ru.gamebot.platform.domain.repository;

import java.util.List;
import ru.gamebot.platform.domain.enums.RewardRequestStatus;
import ru.gamebot.platform.domain.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.RewardRequest;

public interface RewardRequestRepository extends JpaRepository<RewardRequest, Long> {

    void deleteAllByUser(AppUser user);

    List<RewardRequest> findAllByStatusOrderByCreatedAtAsc(RewardRequestStatus status);

    List<RewardRequest> findAllByUserOrderByCreatedAtDesc(AppUser user);

    long countByStatus(RewardRequestStatus status);
}
