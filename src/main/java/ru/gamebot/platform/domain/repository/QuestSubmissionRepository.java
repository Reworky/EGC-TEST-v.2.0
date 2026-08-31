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

    @Query("SELECT COUNT(s) FROM QuestSubmission s WHERE s.user = :user AND s.quest.gameName = :gameName AND (:category IS NULL AND s.quest.category IS NULL OR s.quest.category = :category) AND s.status = 'APPROVED' AND s.updatedAt >= :since")
    long countApprovedByUserAndGameAndCategorySince(@Param("user") AppUser user, @Param("gameName") String gameName, @Param("category") String category, @Param("since") LocalDateTime since);

    @Query("SELECT MAX(s.updatedAt) FROM QuestSubmission s WHERE s.user = :user AND s.quest.gameName = :gameName AND s.status = 'APPROVED'")
    Optional<LocalDateTime> findLastApprovedDateByUserAndGame(@Param("user") AppUser user, @Param("gameName") String gameName);

    @Query("SELECT MAX(s.updatedAt) FROM QuestSubmission s WHERE s.user = :user AND s.quest.gameName = :gameName AND (:category IS NULL AND s.quest.category IS NULL OR s.quest.category = :category) AND s.status = 'APPROVED'")
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

    @Query("SELECT COUNT(s) FROM QuestSubmission s WHERE s.status = 'APPROVED' AND s.updatedAt >= :since")
    long countApprovedSince(@Param("since") LocalDateTime since);

    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM QuestSubmission s " +
           "WHERE s.user = :user AND s.status = 'APPROVED' AND s.updatedAt >= :since")
    boolean existsApprovedByUserSince(@Param("user") AppUser user, @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(s) FROM QuestSubmission s WHERE s.status IN ('APPROVED', 'REJECTED')")
    long countModerated();

    @Query("SELECT COALESCE(SUM(s.quest.rewardCoins), 0) FROM QuestSubmission s WHERE s.status = 'APPROVED'")
    long sumAllIssuedCoins();

    @Query("SELECT COALESCE(SUM(s.quest.rewardCoins), 0) FROM QuestSubmission s WHERE s.user = :user AND s.status = 'APPROVED'")
    long sumIssuedCoinsByUser(@Param("user") AppUser user);

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

    @Query("SELECT s.quest.id, s.quest.title, s.quest.gameName, s.quest.rewardCoins, COUNT(s) as cnt FROM QuestSubmission s WHERE s.status = 'APPROVED' AND s.updatedAt >= :since GROUP BY s.quest.id, s.quest.title, s.quest.gameName, s.quest.rewardCoins ORDER BY cnt DESC")
    List<Object[]> findTopQuestsByCompletionsSince(@Param("since") java.time.LocalDateTime since);

    /** Антифрод-аудит: аккаунты с 2+ одобренными заявками по квесту, помеченному oneTimePerAccount —
     *  это те, кто фармил такой квест ДО фикса (проверка текущего состояния аккаунта вместо действия). */
    @Query("SELECT s.user.id, s.user.nickname, s.user.telegramId, s.quest.title, s.quest.gameName, s.quest.rewardCoins, COUNT(s) as cnt " +
           "FROM QuestSubmission s WHERE s.quest.oneTimePerAccount = true AND s.status = 'APPROVED' " +
           "GROUP BY s.user.id, s.user.nickname, s.user.telegramId, s.quest.title, s.quest.gameName, s.quest.rewardCoins " +
           "HAVING COUNT(s) > 1 ORDER BY cnt DESC")
    List<Object[]> findOneTimeQuestRepeatOffenders();

    @EntityGraph(attributePaths = {"user", "quest"})
    @Query("SELECT s FROM QuestSubmission s WHERE s.user = :user AND s.status = 'APPROVED' ORDER BY s.updatedAt DESC")
    List<QuestSubmission> findAllApprovedByUserOrderByUpdatedAtDesc(@Param("user") AppUser user);

    @Query("SELECT s FROM QuestSubmission s WHERE s.quest = :quest AND s.status IN ('DRAFT','PENDING','REJECTED','NEEDS_INFO')")
    List<QuestSubmission> findActiveByQuest(@Param("quest") Quest quest);

    @Query("SELECT COUNT(s) FROM QuestSubmission s WHERE s.status IN ('DRAFT','PENDING','NEEDS_INFO') AND (s.expiresAt IS NULL OR s.expiresAt > CURRENT_TIMESTAMP)")
    long countActiveInProgress();

    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM QuestSubmission s " +
           "WHERE s.user.id <> :userId AND s.photoUniqueIds IS NOT NULL " +
           "AND s.photoUniqueIds <> '' AND s.photoUniqueIds LIKE CONCAT('%', :uid, '%')")
    boolean existsByPhotoUniqueIdFromOtherUser(@Param("uid") String uid, @Param("userId") Long userId);

    /** Сумма EXC (rewardCoins) по одобренным заявкам пользователя за период — для дайджеста. */
    @Query("SELECT COALESCE(SUM(s.quest.rewardCoins), 0) FROM QuestSubmission s WHERE s.user = :user AND s.status = 'APPROVED' AND s.updatedAt >= :from AND s.updatedAt < :to")
    long sumApprovedCoinsByUserBetween(@Param("user") AppUser user, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Рейтинг пользователей по XP за прошлую неделю: [userId, xpSum], по убыванию — для дайджеста. */
    @Query("SELECT s.user.id, SUM(s.quest.rewardXp) FROM QuestSubmission s WHERE s.status = 'APPROVED' AND s.updatedAt >= :from AND s.updatedAt < :to GROUP BY s.user.id ORDER BY SUM(s.quest.rewardXp) DESC")
    List<Object[]> findUserXpRankingBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Все активные заявки (DRAFT/PENDING) у которых истёк дедлайн — для автоотмены. */
    @EntityGraph(attributePaths = {"user", "quest"})
    @Query("SELECT s FROM QuestSubmission s WHERE s.status IN ('DRAFT','PENDING') AND s.expiresAt IS NOT NULL AND s.expiresAt < CURRENT_TIMESTAMP")
    List<QuestSubmission> findExpiredActive();

    /** Активные заявки, дедлайн которых наступит в интервале (now, upperBound], предупреждение ещё не отправлено. */
    @EntityGraph(attributePaths = {"user", "quest"})
    @Query("SELECT s FROM QuestSubmission s WHERE s.status IN ('DRAFT','PENDING') AND s.expiresAt IS NOT NULL AND s.expiresAt > CURRENT_TIMESTAMP AND s.expiresAt <= :upperBound AND s.deadlineWarningSent = false")
    List<QuestSubmission> findExpiringBefore(@Param("upperBound") LocalDateTime upperBound);

    // 24ч кулдаун — обычные квесты (все кроме «Сложные» и спонсорских)
    @Query("SELECT s.user.telegramId, s.quest.gameName, s.quest.title " +
           "FROM QuestSubmission s " +
           "WHERE s.status = 'APPROVED' AND s.quest.category <> 'Сложные' AND s.quest.sponsored = false " +
           "GROUP BY s.user.telegramId, s.quest.id, s.quest.gameName, s.quest.title " +
           "HAVING MAX(s.updatedAt) BETWEEN :from AND :to")
    List<Object[]> findUsersWhoseNormalQuestCooldownExpiredBetween(
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to);

    // 336ч кулдаун — только «Сложные» квесты (спонсорские исключены)
    @Query("SELECT s.user.telegramId, s.quest.gameName, s.quest.title " +
           "FROM QuestSubmission s " +
           "WHERE s.status = 'APPROVED' AND s.quest.category = 'Сложные' AND s.quest.sponsored = false " +
           "GROUP BY s.user.telegramId, s.quest.id, s.quest.gameName, s.quest.title " +
           "HAVING MAX(s.updatedAt) BETWEEN :from AND :to")
    List<Object[]> findUsersWhoseHardQuestCooldownExpiredBetween(
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to);

    /** Незавершённые заявки на квесты с включённой авто-верификацией через Brawl Stars API, у пользователя привязан тег, срок не истёк. */
    @EntityGraph(attributePaths = {"user", "quest"})
    @Query("SELECT s FROM QuestSubmission s WHERE s.status = 'DRAFT' AND s.quest.brawlVerifyType IS NOT NULL " +
           "AND s.user.brawlStarsTag IS NOT NULL AND (s.expiresAt IS NULL OR s.expiresAt > CURRENT_TIMESTAMP)")
    List<QuestSubmission> findInProgressBrawlAutoVerify();
}
