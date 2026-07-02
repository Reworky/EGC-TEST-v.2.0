package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.enums.RewardRequestStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.RewardItem;
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

    long countByStatusIn(java.util.Collection<RewardRequestStatus> statuses);

    @EntityGraph(attributePaths = {"user", "rewardItem"})
    List<RewardRequest> findAllByStatusAndRewardItemCategoryOrderByCreatedAtAsc(RewardRequestStatus status, String category);

    void deleteAllByRewardItem(RewardItem rewardItem);

    @Query("SELECT COUNT(r) FROM RewardRequest r WHERE r.user = :user AND r.rewardItem.purchaseGroup = :group AND r.status NOT IN ('CANCELLED', 'REJECTED') AND r.createdAt >= :since")
    long countActiveByUserAndGroupSince(@Param("user") AppUser user, @Param("group") String group, @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(r) FROM RewardRequest r WHERE r.user = :user AND r.rewardItem.purchaseGroup = :group AND r.status NOT IN ('CANCELLED', 'REJECTED')")
    long countActiveByUserAndGroupAllTime(@Param("user") AppUser user, @Param("group") String group);

    @Query("SELECT COUNT(r) FROM RewardRequest r WHERE r.user = :user AND r.rewardItem.purchaseGroup = 'council_egc' AND r.status NOT IN ('CANCELLED', 'REJECTED') AND r.createdAt >= :since")
    long countActiveCouncilSince(@Param("user") AppUser user, @Param("since") LocalDateTime since);
}
