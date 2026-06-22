package ru.gamebot.platform.domain.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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

    List<AppUser> findAllByFraudSuspectTrue();
}
