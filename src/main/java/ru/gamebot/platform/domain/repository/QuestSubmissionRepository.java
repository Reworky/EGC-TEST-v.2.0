package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.QuestSubmission;

public interface QuestSubmissionRepository extends JpaRepository<QuestSubmission, Long> {

    @EntityGraph(attributePaths = {"user", "quest"})
    List<QuestSubmission> findAllByStatusOrderByCreatedAtAsc(SubmissionStatus status);

    @EntityGraph(attributePaths = {"user", "quest"})
    List<QuestSubmission> findAllByUserOrderByCreatedAtDesc(AppUser user);

    @EntityGraph(attributePaths = {"user", "quest"})
    Optional<QuestSubmission> findTopByUserAndQuestOrderByCreatedAtDesc(AppUser user, Quest quest);

    @EntityGraph(attributePaths = {"user", "quest"})
    Optional<QuestSubmission> findWithUserAndQuestById(Long id);

    long countByStatus(SubmissionStatus status);

    long countByQuest(Quest quest);

    void deleteAllByUser(AppUser user);

    void deleteAllByQuest(Quest quest);

    @Query("SELECT COUNT(s) FROM QuestSubmission s WHERE s.user = :user AND s.quest.gameName = :gameName AND s.quest.category = :category AND s.status = 'APPROVED' AND s.updatedAt >= :since")
    long countApprovedByUserAndGameAndCategorySince(@Param("user") AppUser user, @Param("gameName") String gameName, @Param("category") String category, @Param("since") LocalDateTime since);

    @Query("SELECT MAX(s.updatedAt) FROM QuestSubmission s WHERE s.user = :user AND s.quest.gameName = :gameName AND s.status = 'APPROVED'")
    Optional<LocalDateTime> findLastApprovedDateByUserAndGame(@Param("user") AppUser user, @Param("gameName") String gameName);

    @Query("SELECT COUNT(s) FROM QuestSubmission s WHERE s.user = :user AND s.status IN ('APPROVED', 'REJECTED', 'NEEDS_INFO')")
    long countReviewedByUser(@Param("user") AppUser user);

    @Query("SELECT COUNT(s) FROM QuestSubmission s WHERE s.user = :user AND s.status = 'APPROVED'")
    long countApprovedByUser(@Param("user") AppUser user);

    @Query("SELECT s.createdAt FROM QuestSubmission s WHERE s.user = :user AND s.status = 'PENDING' ORDER BY s.createdAt DESC")
    List<LocalDateTime> findRecentPendingSubmissionTimes(@Param("user") AppUser user);

}
