package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.model.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByTelegramId(Long telegramId);

    List<AppUser> findAllByRegistrationCompletedTrueOrderByXpDescTelegramIdAsc();

    List<AppUser> findAllByRegistrationCompletedTrueOrderByWeeklyXpDescTelegramIdAsc();

    List<AppUser> findTop20ByRegistrationCompletedTrueOrderByXpDescTelegramIdAsc();

    List<AppUser> findTop20ByRegistrationCompletedTrueOrderByWeeklyXpDescTelegramIdAsc();

    long countByRegistrationCompletedTrue();

    @Query("SELECT COALESCE(SUM(u.coins), 0) FROM AppUser u")
    long sumAllCoins();

    @Query("SELECT COUNT(u) FROM AppUser u WHERE u.registrationCompleted = true AND u.createdAt >= :since")
    long countNewUsersSince(@Param("since") LocalDateTime since);

    List<AppUser> findTop5ByRegistrationCompletedTrueOrderByXpDescTelegramIdAsc();

    List<AppUser> findAllByFraudSuspectTrue();

    List<AppUser> findAllByTrafficSourceCodeOrderByCreatedAtDesc(String trafficSourceCode);

    long countByTrafficSourceCode(String trafficSourceCode);
}
