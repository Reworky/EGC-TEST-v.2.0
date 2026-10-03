package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /** Живые заявки на вывод ДРУГИХ игроков с реквизитами — для проверки «реквизиты уже используются другим аккаунтом». */
    @Query("SELECT r FROM RewardRequest r JOIN FETCH r.user WHERE r.rewardItem.category = 'Вывод' AND r.payoutDetails IS NOT NULL "
            + "AND r.user.id <> :userId AND r.status IN ('PENDING', 'IN_PROGRESS', 'APPROVED')")
    List<RewardRequest> findActiveWithdrawalsWithDetailsOfOtherUsers(@Param("userId") Long userId);

    /** Блокировка строки заявки на время транзакции (SELECT ... FOR UPDATE): одобрение, отклонение и отмена одной заявки
     *  идут строго по очереди, иначе отмена игроком во время выплаты давала и деньги, и возврат EXC (аудит вывода 2026-10-03). */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RewardRequest r WHERE r.id = :id")
    java.util.Optional<RewardRequest> findByIdForUpdate(@Param("id") Long id);

    long countByStatus(RewardRequestStatus status);

    long countByStatusIn(java.util.Collection<RewardRequestStatus> statuses);

    @Query("SELECT COUNT(r) FROM RewardRequest r WHERE r.status IN :statuses AND r.rewardItem.category <> 'Вывод'")
    long countNonWithdrawalByStatusIn(@Param("statuses") java.util.Collection<RewardRequestStatus> statuses);

    @Query("SELECT DISTINCT r FROM RewardRequest r JOIN FETCH r.user JOIN FETCH r.rewardItem WHERE r.status = :status AND r.rewardItem.category = :category ORDER BY r.createdAt ASC")
    List<RewardRequest> findAllByStatusAndRewardItemCategoryOrderByCreatedAtAsc(@Param("status") RewardRequestStatus status, @Param("category") String category);

    void deleteAllByRewardItem(RewardItem rewardItem);

    @Query("SELECT COUNT(r) FROM RewardRequest r WHERE r.user = :user AND r.rewardItem.category = 'Вывод' AND r.status = 'PENDING'")
    long countPendingWithdrawalsByUser(@Param("user") AppUser user);

    @Query("SELECT COUNT(r) FROM RewardRequest r WHERE r.user = :user AND r.rewardItem.category = 'Вывод' AND r.status NOT IN ('CANCELLED', 'REJECTED') AND r.createdAt >= :since")
    long countWithdrawalsByUserSince(@Param("user") AppUser user, @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(r) FROM RewardRequest r WHERE r.user = :user AND r.rewardItem.purchaseGroup = :group AND r.status NOT IN ('CANCELLED', 'REJECTED') AND r.createdAt >= :since")
    long countActiveByUserAndGroupSince(@Param("user") AppUser user, @Param("group") String group, @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(r) FROM RewardRequest r WHERE r.user = :user AND r.rewardItem.purchaseGroup = :group AND r.status NOT IN ('CANCELLED', 'REJECTED')")
    long countActiveByUserAndGroupAllTime(@Param("user") AppUser user, @Param("group") String group);

    @Query("SELECT COUNT(r) FROM RewardRequest r WHERE r.user = :user AND r.rewardItem.purchaseGroup = 'council_egc' AND r.status NOT IN ('CANCELLED', 'REJECTED') AND r.createdAt >= :since")
    long countActiveCouncilSince(@Param("user") AppUser user, @Param("since") LocalDateTime since);

    @Query("SELECT COALESCE(MAX(r.displayId), 0) FROM RewardRequest r WHERE r.rewardItem.category = 'Вывод'")
    long findMaxWithdrawalDisplayId();

    @Query("SELECT r FROM RewardRequest r JOIN FETCH r.user JOIN FETCH r.rewardItem WHERE r.rewardItem.category = 'Вывод' AND r.status IN ('APPROVED','PENDING','IN_PROGRESS') AND r.payoutDetails LIKE :needle AND r.user <> :excludeUser")
    List<RewardRequest> findApprovedWithdrawalsWithPayoutDetailsContaining(@Param("needle") String needle, @Param("excludeUser") AppUser excludeUser);

    // COALESCE на paidPriceCoins — снимок реально списанной суммы (см. RewardService.actualPaidPrice).
    // rewardItem.priceCoins один на всех, кто когда-либо купил эту позицию каталога (звёзды, игровая
    // валюта) — без снимка сумма "плыла" бы каждый раз, когда меняется Health Ratio.
    @Query("SELECT COALESCE(SUM(COALESCE(r.paidPriceCoins, r.rewardItem.priceCoins)), 0) FROM RewardRequest r WHERE r.rewardItem.category = 'Вывод' AND r.status = 'APPROVED'")
    long sumApprovedWithdrawalExc();

    /** Приближение — фильтр по createdAt заявки, не по моменту одобрения (отдельного поля даты
     *  одобрения у RewardRequest нет). Для отчёта под рекламодателя точность до дня не критична. */
    @Query("SELECT COALESCE(SUM(COALESCE(r.paidPriceCoins, r.rewardItem.priceCoins)), 0) FROM RewardRequest r WHERE r.rewardItem.category = 'Вывод' AND r.status = 'APPROVED' AND r.createdAt >= :since")
    long sumApprovedWithdrawalExcSince(@Param("since") LocalDateTime since);

    @Query("SELECT COUNT(DISTINCT r.user.id) FROM RewardRequest r WHERE r.rewardItem.category = 'Вывод' AND r.status = 'APPROVED'")
    long countDistinctUsersWithApprovedWithdrawals();

    @EntityGraph(attributePaths = {"user", "rewardItem"})
    Page<RewardRequest> findAllByRewardItemCategoryOrderByCreatedAtDesc(String category, Pageable pageable);

    @Query("SELECT COALESCE(MAX(r.displayId), 0) FROM RewardRequest r WHERE r.rewardItem.category <> 'Вывод'")
    long findMaxShopDisplayId();

    @EntityGraph(attributePaths = {"rewardItem"})
    @Query("SELECT r FROM RewardRequest r WHERE r.rewardItem.category = 'Вывод' AND r.status = 'APPROVED'")
    List<RewardRequest> findAllApprovedWithdrawals();

    /** Лёгкая выборка выплаченных выводов для вкладки «Выводы»: игрок, момент (выплата, а у старых заявок без paid_at - создание),
     *  списанные EXC, рубли из заявки, группа позиции (Stars) и реквизиты (TON). */
    @Query("SELECT r.user.id, COALESCE(r.paidAt, r.createdAt), COALESCE(r.paidPriceCoins, r.rewardItem.priceCoins), "
            + "COALESCE(r.fixedRubValue, 0L), r.rewardItem.purchaseGroup, r.payoutDetails "
            + "FROM RewardRequest r WHERE r.rewardItem.category = 'Вывод' AND r.status = 'APPROVED'")
    List<Object[]> findApprovedWithdrawalRows();

    /** Та же приближённая фильтрация по createdAt, что и в sumApprovedWithdrawalExcSince — для
     *  недельного/месячного отчёта по выводам (см. RewardService.withdrawalStatsSince). */
    @EntityGraph(attributePaths = {"rewardItem"})
    @Query("SELECT r FROM RewardRequest r WHERE r.rewardItem.category = 'Вывод' AND r.status = 'APPROVED' AND r.createdAt >= :since")
    List<RewardRequest> findApprovedWithdrawalsSince(@Param("since") LocalDateTime since);

    /** Сколько разных игроков получили выплату (для поста «сводка выплат» в канале). */
    @Query("SELECT COUNT(DISTINCT r.user.id) FROM RewardRequest r WHERE r.rewardItem.category = 'Вывод' AND r.status = 'APPROVED' AND r.createdAt >= :since")
    long countDistinctWithdrawalUsersSince(@Param("since") LocalDateTime since);

    /** Популярное в магазине: число заказов по товарам каталога (без вывода и отменённых) с даты. Строки: [id товара, число заказов], от большего к меньшему. */
    @Query("SELECT r.rewardItem.id, COUNT(r) FROM RewardRequest r WHERE r.rewardItem.category <> 'Вывод' AND r.status IN ('APPROVED','PENDING','IN_PROGRESS') AND r.createdAt >= :since GROUP BY r.rewardItem.id ORDER BY COUNT(r) DESC")
    List<Object[]> countShopOrdersByItemSince(@Param("since") LocalDateTime since);
}
