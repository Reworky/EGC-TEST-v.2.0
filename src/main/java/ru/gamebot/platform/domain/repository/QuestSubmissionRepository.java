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

    @Query("SELECT COUNT(s) FROM QuestSubmission s WHERE s.quest = :quest AND s.status = 'APPROVED'")
    long countApprovedByQuest(@Param("quest") Quest quest);

    @Query("SELECT COUNT(s) FROM QuestSubmission s WHERE s.quest = :quest AND s.status = 'APPROVED' AND s.updatedAt >= :from AND s.updatedAt < :to")
    long countApprovedByQuestBetween(@Param("quest") Quest quest,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

    void deleteAllByUser(AppUser user);

    void deleteAllByQuest(Quest quest);

    @Query("SELECT COUNT(s) FROM QuestSubmission s WHERE s.user = :user AND s.quest.gameName = :gameName AND s.quest.category = :category AND s.status = 'APPROVED' AND s.updatedAt >= :since")
    long countApprovedByUserAndGameAndCategorySince(@Param("user") AppUser user, @Param("gameName") String gameName, @Param("category") String category, @Param("since") LocalDateTime since);

    @Query("SELECT MAX(s.updatedAt) FROM QuestSubmission s WHERE s.user = :user AND s.quest.gameName = :gameName AND s.status = 'APPROVED'")
    Optional<LocalDateTime> findLastApprovedDateByUserAndGame(@Param("user") AppUser user, @Param("gameName") String gameName);

    @Query("SELECT MAX(s.updatedAt) FROM QuestSubmission s WHERE s.user = :user AND s.quest.gameName = :gameName AND s.quest.category = :category AND s.status = 'APPROVED'")
    Optional<LocalDateTime> findLastApprovedDateByUserAndGameAndCategory(@Param("user") AppUser user, @Param("gameName") String gameName, @Param("category") String category);

    @Query("SELECT MAX(s.updatedAt) FROM QuestSubmission s WHERE s.user = :user AND s.quest = :quest AND s.status = 'APPROVED'")
    Optional<LocalDateTime> findLastApprovedDateByUserAndQuest(@Param("user") AppUser user, @Param("quest") Quest quest);

    @Query("SELECT COUNT(s) FROM QuestSubmission s WHERE s.user = :user AND s.status IN ('APPROVED', 'REJECTED', 'NEEDS_INFO')")
    long countReviewedByUser(@Param("user") AppUser user);

    @Query("SELECT COUNT(s) FROM QuestSubmission s WHERE s.user = :user AND s.status = 'APPROVED'")
    long countApprovedByUser(@Param("user") AppUser user);

    @Query("SELECT s.createdAt FROM QuestSubmission s WHERE s.user = :user AND s.status = 'PENDING' ORDER BY s.createdAt DESC")
    List<LocalDateTime> findRecentPendingSubmissionTimes(@Param("user") AppUser user);

    @Query("SELECT COUNT(s) FROM QuestSubmission s WHERE s.status = 'APPROVED'")
    long countAllApproved();

    @Query("SELECT COALESCE(SUM(s.quest.rewardCoins), 0) FROM QuestSubmission s WHERE s.status = 'APPROVED'")
    long sumAllIssuedCoins();

    @Query("SELECT s.quest.gameName FROM QuestSubmission s WHERE s.status = 'APPROVED' GROUP BY s.quest.gameName ORDER BY COUNT(s) DESC")
    List<String> findTopGameNames();

    @Query("SELECT COALESCE(MAX(s.displayId), 0) FROM QuestSubmission s")
    long findMaxDisplayId();

    @Query("SELECT COALESCE(MAX(s.completionDisplayId), 0) FROM QuestSubmission s")
    long findMaxCompletionDisplayId();

    @Query("SELECT COUNT(s) FROM QuestSubmission s WHERE s.user = :user AND s.status = 'APPROVED' AND s.updatedAt >= :since AND s.updatedAt < :until")
    long countApprovedByUserBetween(@Param("user") AppUser user, @Param("since") LocalDateTime since, @Param("until") LocalDateTime until);

    @Query("SELECT s.quest.id, s.quest.title, s.quest.gameName, s.quest.category, COUNT(s) as cnt FROM QuestSubmission s WHERE s.status = 'APPROVED' GROUP BY s.quest.id, s.quest.title, s.quest.gameName, s.quest.category ORDER BY cnt DESC")
    List<Object[]> findTopQuestsByCompletions();

    @EntityGraph(attributePaths = {"user", "quest"})
    @Query("SELECT s FROM QuestSubmission s WHERE s.user = :user AND s.status = 'APPROVED' ORDER BY s.updatedAt DESC")
    List<QuestSubmission> findAllApprovedByUserOrderByUpdatedAtDesc(@Param("user") AppUser user);

    @Query("SELECT s FROM QuestSubmission s WHERE s.quest = :quest AND s.status IN ('DRAFT','PENDING','REJECTED','NEEDS_INFO')")
    List<QuestSubmission> findActiveByQuest(@Param("quest") Quest quest);

    @Query("SELECT COUNT(s) FROM QuestSubmission s WHERE s.status IN ('DRAFT','PENDING','NEEDS_INFO') AND (s.expiresAt IS NULL OR s.expiresAt > CURRENT_TIMESTAMP)")
    long countActiveInProgress();
}
