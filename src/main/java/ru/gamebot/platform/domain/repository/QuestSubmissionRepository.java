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
    List<QuestSubmission> findAllByQuestAndStatusOrderByUpdatedAtAsc(Quest quest, SubmissionStatus status);

    @EntityGraph(attributePaths = {"user", "quest"})
    Optional<QuestSubmission> findWithUserAndQuestById(Long id);

    Optional<QuestSubmission> findFirstByUserAndStatusOrderByUpdatedAtAsc(AppUser user, SubmissionStatus status);

    long countByStatus(SubmissionStatus status);

    long countByQuest(Quest quest);

    @Query("SELECT COUNT(s) FROM QuestSubmission s WHERE s.quest = :quest AND s.status = 'APPROVED'")
    long countApprovedByQuest(@Param("quest") Quest quest);

    @Query("SELECT COUNT(s) FROM QuestSubmission s WHERE s.quest = :quest AND s.status = 'APPROVED' AND s.updatedAt >= :from AND s.updatedAt < :to")
    long countApprovedByQuestBetween(@Param("quest") Quest quest,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(s) FROM QuestSubmission s WHERE s.user = :user AND s.quest = :quest AND s.status = 'APPROVED' AND s.updatedAt >= :since")
    long countApprovedByUserAndQuestSince(@Param("user") AppUser user, @Param("quest") Quest quest, @Param("since") LocalDateTime since);

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

    /** Уникальные игроки, у кого одобрен хотя бы один квест с такого-то момента — для метрики
     *  "% выполнивших квест за неделю" (см. UserService.getEngagementReport). */
    @Query("SELECT COUNT(DISTINCT s.user) FROM QuestSubmission s WHERE s.status = 'APPROVED' AND s.updatedAt >= :since")
    long countDistinctUsersWithApprovedSince(@Param("since") LocalDateTime since);

    /** То же самое, с фильтром по источнику трафика и минимальному возрасту аккаунта — см.
     *  AppUserRepository.countDistinctActiveSince для семантики sourceFilter/maxCreatedAt. */
    @Query("SELECT COUNT(DISTINCT s.user) FROM QuestSubmission s WHERE s.status = 'APPROVED' AND s.updatedAt >= :since "
            + "AND (:sourceFilter IS NULL OR (:sourceFilter = 'ORGANIC' AND s.user.trafficSourceCode IS NULL) OR s.user.trafficSourceCode = :sourceFilter) "
            + "AND (:maxCreatedAt IS NULL OR s.user.createdAt <= :maxCreatedAt)")
    long countDistinctUsersWithApprovedSince(@Param("since") LocalDateTime since, @Param("sourceFilter") String sourceFilter, @Param("maxCreatedAt") LocalDateTime maxCreatedAt);

    /** Telegram ID игрока + дата одобрения для каждого одобренного квеста — сырьё для расчёта
     *  ретеншена "вернулся ли за вторым квестом в течение недели после первого" в Java, а не в JPQL
     *  (оконные функции по группам неудобно/невозможно выразить переносимо между H2 и Postgres). */
    @Query("SELECT s.user.telegramId, s.updatedAt FROM QuestSubmission s WHERE s.status = 'APPROVED'")
    List<Object[]> findApprovedUserIdAndDateForRetention();

    /** То же самое, с фильтром по источнику трафика и минимальному возрасту аккаунта — см.
     *  AppUserRepository.countDistinctActiveSince для семантики sourceFilter/maxCreatedAt. */
    @Query("SELECT s.user.telegramId, s.updatedAt FROM QuestSubmission s WHERE s.status = 'APPROVED' "
            + "AND (:sourceFilter IS NULL OR (:sourceFilter = 'ORGANIC' AND s.user.trafficSourceCode IS NULL) OR s.user.trafficSourceCode = :sourceFilter) "
            + "AND (:maxCreatedAt IS NULL OR s.user.createdAt <= :maxCreatedAt)")
    List<Object[]> findApprovedUserIdAndDateForRetention(@Param("sourceFilter") String sourceFilter, @Param("maxCreatedAt") LocalDateTime maxCreatedAt);

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

    /** Тот же скриншот, повторно поданный ТЕМ ЖЕ игроком в другой заявке (фарм одного отчёта) —
     *  existsByPhotoUniqueIdFromOtherUser этот случай намеренно не ловит, он только про чужие заявки. */
    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END FROM QuestSubmission s " +
           "WHERE s.user.id = :userId AND s.id <> :submissionId AND s.photoUniqueIds IS NOT NULL " +
           "AND s.photoUniqueIds <> '' AND s.photoUniqueIds LIKE CONCAT('%', :uid, '%')")
    boolean existsByPhotoUniqueIdFromSameUser(@Param("uid") String uid, @Param("userId") Long userId, @Param("submissionId") Long submissionId);

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

    // 24ч кулдаун — единый для всех неспонсорских квестов (см. QuestService.cooldownHours, старая
    // отдельная 336ч-ветка для category="Сложные" убрана 2026-09-17 — категории нигде не видны
    // игроку, было молчаливое зависание кулдауна там, где игрок не мог понять причину). Раньше
    // здесь ещё был отдельный findUsersWhoseHardQuestCooldownExpiredBetween-запрос на 336ч для «Сложные» —
    // убран вместе с этой веткой, иначе игрок стал бы фактически доступен для повтора через 24ч
    // (по актуальному cooldownHours), но уведомление об этом пришло бы только через 14 дней.
    @Query("SELECT s.user.telegramId, s.quest.gameName, s.quest.title " +
           "FROM QuestSubmission s " +
           "WHERE s.status = 'APPROVED' AND s.quest.sponsored = false " +
           "GROUP BY s.user.telegramId, s.quest.id, s.quest.gameName, s.quest.title " +
           "HAVING MAX(s.updatedAt) BETWEEN :from AND :to")
    List<Object[]> findUsersWhoseNormalQuestCooldownExpiredBetween(
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to);

    /** Повторное напоминание (2026-09-20, запрошено пользователем) — игрок получил первое "кулдаун
     *  снят" уведомление, но так и не взял квест заново. Берём последнюю (MAX updatedAt) одобренную
     *  заявку на пару (пользователь, квест) через коррелированный подзапрос — если бы игрок уже взял
     *  квест снова, появилась бы более новая APPROVED-заявка и она стала бы MAX, эта строка перестала
     *  бы сюда попадать (то же свойство, что уже использует findUsersWhoseNormalQuestCooldownExpiredBetween
     *  выше, просто нужны сами сущности, а не только имена — чтобы проставить cooldownReminderSentAt). */
    @EntityGraph(attributePaths = {"user", "quest"})
    @Query("SELECT s FROM QuestSubmission s WHERE s.status = 'APPROVED' AND s.quest.sponsored = false " +
           "AND s.cooldownReminderSentAt IS NULL AND s.updatedAt <= :cutoff " +
           "AND s.updatedAt = (SELECT MAX(s2.updatedAt) FROM QuestSubmission s2 " +
           "WHERE s2.user = s.user AND s2.quest = s.quest AND s2.status = 'APPROVED')")
    List<QuestSubmission> findApprovedNeedingCooldownReminder(@Param("cutoff") java.time.LocalDateTime cutoff);

    /** Для отчёта "Активность автоквестов" — сколько разных игроков получили одобрение по игре с указанной даты. */
    @Query("SELECT COUNT(DISTINCT s.user.id) FROM QuestSubmission s " +
           "WHERE s.quest.gameName = :gameName AND s.status = 'APPROVED' AND s.updatedAt >= :since")
    long countDistinctApprovedUsersByGameSince(@Param("gameName") String gameName, @Param("since") LocalDateTime since);

    /** Для отчёта "Активность автоквестов" — сколько всего одобрений по игре с указанной даты. */
    @Query("SELECT COUNT(s) FROM QuestSubmission s " +
           "WHERE s.quest.gameName = :gameName AND s.status = 'APPROVED' AND s.updatedAt >= :since")
    long countApprovedByGameSince(@Param("gameName") String gameName, @Param("since") LocalDateTime since);

    /** Незавершённые заявки на квесты с включённой авто-верификацией через Brawl Stars API, у пользователя привязан тег, срок не истёк. */
    @EntityGraph(attributePaths = {"user", "quest"})
    @Query("SELECT s FROM QuestSubmission s WHERE s.status = 'DRAFT' AND s.quest.brawlVerifyType IS NOT NULL " +
           "AND s.user.brawlStarsTag IS NOT NULL AND (s.expiresAt IS NULL OR s.expiresAt > CURRENT_TIMESTAMP)")
    List<QuestSubmission> findInProgressBrawlAutoVerify();

    /** Незавершённые заявки на квесты с включённой авто-верификацией через Clash of Clans API, у пользователя привязан тег, срок не истёк. */
    @EntityGraph(attributePaths = {"user", "quest"})
    @Query("SELECT s FROM QuestSubmission s WHERE s.status = 'DRAFT' AND s.quest.clashVerifyType IS NOT NULL " +
           "AND s.user.clashOfClansTag IS NOT NULL AND (s.expiresAt IS NULL OR s.expiresAt > CURRENT_TIMESTAMP)")
    List<QuestSubmission> findInProgressClashAutoVerify();

    /** Незавершённые заявки на квесты с включённой авто-верификацией через Clash Royale API, у пользователя привязан тег, срок не истёк. */
    @EntityGraph(attributePaths = {"user", "quest"})
    @Query("SELECT s FROM QuestSubmission s WHERE s.status = 'DRAFT' AND s.quest.clashRoyaleVerifyType IS NOT NULL " +
           "AND s.user.clashRoyaleTag IS NOT NULL AND (s.expiresAt IS NULL OR s.expiresAt > CURRENT_TIMESTAMP)")
    List<QuestSubmission> findInProgressClashRoyaleAutoVerify();

    /** Незавершённые заявки на квесты с включённой авто-верификацией через Steam Web API (Dota 2), у пользователя привязан аккаунт, срок не истёк. */
    @EntityGraph(attributePaths = {"user", "quest"})
    @Query("SELECT s FROM QuestSubmission s WHERE s.status = 'DRAFT' AND s.quest.dotaVerifyType IS NOT NULL " +
           "AND s.user.dotaAccountId IS NOT NULL AND (s.expiresAt IS NULL OR s.expiresAt > CURRENT_TIMESTAMP)")
    List<QuestSubmission> findInProgressDotaAutoVerify();

    /** Незавершённые заявки на квесты с включённой авто-верификацией через Steam Web API (CS2), у пользователя привязан SteamID64, срок не истёк. */
    @EntityGraph(attributePaths = {"user", "quest"})
    @Query("SELECT s FROM QuestSubmission s WHERE s.status = 'DRAFT' AND s.quest.cs2VerifyType IS NOT NULL " +
           "AND s.user.cs2SteamId64 IS NOT NULL AND (s.expiresAt IS NULL OR s.expiresAt > CURRENT_TIMESTAMP)")
    List<QuestSubmission> findInProgressCs2AutoVerify();

    /** Незавершённые заявки на квесты с включённой авто-верификацией через официальный PUBG API (PC), у пользователя привязан accountId, срок не истёк. */
    @EntityGraph(attributePaths = {"user", "quest"})
    @Query("SELECT s FROM QuestSubmission s WHERE s.status = 'DRAFT' AND s.quest.pubgVerifyType IS NOT NULL " +
           "AND s.user.pubgAccountId IS NOT NULL AND (s.expiresAt IS NULL OR s.expiresAt > CURRENT_TIMESTAMP)")
    List<QuestSubmission> findInProgressPubgAutoVerify();

    /** Одобренные квесты и выданные EXC по каждому игроку, пришедшему по метке закупа
     *  (TrafficFunnelService: доля сделавших 1-й/2-й квест). */
    @Query("SELECT s.user.id, COUNT(s), COALESCE(SUM(s.awardedCoins), 0) FROM QuestSubmission s "
            + "WHERE s.status = 'APPROVED' AND s.user.trafficSourceCode IS NOT NULL GROUP BY s.user.id")
    List<Object[]> countApprovedPerTrafficUser();

    /** Сколько раз квест БРАЛИ с момента since, в разрезе статуса заявки (квест, статус, число) - отличает «никто не берёт»
     *  от «берут, но не доходят до одобрения» и показывает, где очередь модерации, а где отказы (QuestPoolHealthService). */
    @Query("SELECT s.quest.id, s.status, COUNT(s) FROM QuestSubmission s WHERE s.createdAt >= :since GROUP BY s.quest.id, s.status")
    List<Object[]> countTakenByStatusGroupedByQuestSince(@Param("since") LocalDateTime since);

    /** Число одобренных выполнений по каждому квесту с момента since (QuestPoolHealthService). */
    @Query("SELECT s.quest.id, COUNT(s) FROM QuestSubmission s WHERE s.status = 'APPROVED' AND s.updatedAt >= :since GROUP BY s.quest.id")
    List<Object[]> countApprovedGroupedByQuestSince(@Param("since") LocalDateTime since);
}
