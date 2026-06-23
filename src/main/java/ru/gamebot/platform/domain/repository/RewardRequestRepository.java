package ru.gamebot.platform.domain.repository;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.enums.RewardRequestStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.RewardRequest;

public interface RewardRequestRepository extends JpaRepository<RewardRequest, Long> {

    void deleteAllByUser(AppUser user);

    @EntityGraph(attributePaths = {"user", "rewardItem"})
    List<RewardRequest> findAllByStatusOrderByCreatedAtAsc(RewardRequestStatus status);

    @EntityGraph(attributePaths = {"user", "rewardItem"})
    List<RewardRequest> findAllByUserOrderByCreatedAtDesc(AppUser user);

    @EntityGraph(attributePaths = {"user", "rewardItem"})
    List<RewardRequest> findAllByUserAndStatusOrderByCreatedAtDesc(AppUser user, RewardRequestStatus status);

    @EntityGraph(attributePaths = {"user", "rewardItem"})
    java.util.Optional<RewardRequest> findWithUserAndRewardItemById(Long id);

    long countByStatus(RewardRequestStatus status);
}
