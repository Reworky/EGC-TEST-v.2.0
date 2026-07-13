package ru.gamebot.platform.domain.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.model.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByTelegramId(Long telegramId);

    /**
     * Блокирует строку пользователя на время транзакции (SELECT ... FOR UPDATE).
     * Нужно везде, где идёт схема "проверить лимит → записать" (взятие квеста и т.п.),
     * чтобы конкурентные запросы не могли пройти проверку одновременно (гонка состояний).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM AppUser u WHERE u.id = :id")
    Optional<AppUser> findByIdForUpdate(@Param("id") Long id);

    List<AppUser> findAllByRegistrationCompletedTrueOrderByXpDescTelegramIdAsc();

    List<AppUser> findAllByRegistrationCompletedTrueOrderByWeeklyXpDescTelegramIdAsc();

    List<AppUser> findTop20ByRegistrationCompletedTrueOrderByXpDescTelegramIdAsc();

    List<AppUser> findTop20ByRegistrationCompletedTrueOrderByWeeklyXpDescTelegramIdAsc();

    /** Только реально активные на этой неделе игроки — без этого топ-20 добивался нулями (неактивные, но с малым TG ID). */
    List<AppUser> findTop20ByRegistrationCompletedTrueAndWeeklyXpGreaterThanOrderByWeeklyXpDescTelegramIdAsc(long weeklyXp);

    long countByRegistrationCompletedTrue();

    @Query("SELECT COALESCE(SUM(u.coins), 0) FROM AppUser u")
    long sumAllCoins();

    @Query("SELECT COUNT(u) FROM AppUser u WHERE u.registrationCompleted = true AND u.createdAt >= :since")
    long countNewUsersSince(@Param("since") LocalDateTime since);

    List<AppUser> findTop5ByRegistrationCompletedTrueOrderByXpDescTelegramIdAsc();

    List<AppUser> findAllByFraudSuspectTrue();

    List<AppUser> findAllByAvatarFrameColorAndAvatarFrameImageIsNull(String avatarFrameColor);

    List<AppUser> findAllByTrafficSourceCodeOrderByCreatedAtDesc(String trafficSourceCode);

    long countByTrafficSourceCode(String trafficSourceCode);

    @Query("SELECT COUNT(u) FROM AppUser u WHERE u.registrationCompleted = true AND u.lastActivityDate = :today")
    long countActiveOnDate(@Param("today") java.time.LocalDate today);
}
