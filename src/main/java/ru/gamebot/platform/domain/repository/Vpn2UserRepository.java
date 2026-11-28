package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.Vpn2User;

public interface Vpn2UserRepository extends JpaRepository<Vpn2User, Long> {

    Optional<Vpn2User> findByTelegramId(Long telegramId);

    long countByCreatedAtAfter(LocalDateTime after);

    long countByReferredByIsNotNull();

    long countByReferralRewardedTrue();

    long countBySourceCode(String sourceCode);

    /** Напоминания «пробный ещё не взят»: этап stage, регистрация в окне (окно не даёт слать накопившейся старой базе). */
    List<Vpn2User> findByTrialOfferStageAndRemindOffFalseAndCreatedAtBetween(int stage, LocalDateTime from, LocalDateTime to);

    /** Открывали тарифы в окне и об этом ещё не писали. */
    List<Vpn2User> findByTariffNudgedAtIsNullAndRemindOffFalseAndTariffsViewedAtBetween(LocalDateTime from, LocalDateTime to);

    /** Точечное обновление: не перезаписывает остальные поля строки (бонусные дни и рефералку меняет Vpn2Service). */
    @Modifying
    @Transactional
    @Query("update Vpn2User u set u.tariffsViewedAt = :at where u.telegramId = :telegramId")
    void markTariffsViewed(@Param("telegramId") Long telegramId, @Param("at") LocalDateTime at);

    @Modifying
    @Transactional
    @Query("update Vpn2User u set u.remindOff = true where u.telegramId = :telegramId")
    void disableReminders(@Param("telegramId") Long telegramId);

    @Modifying
    @Transactional
    @Query("update Vpn2User u set u.followUpAt = :at where u.telegramId = :telegramId")
    void markFollowUp(@Param("telegramId") Long telegramId, @Param("at") LocalDateTime at);

    @Modifying
    @Transactional
    @Query("update Vpn2User u set u.trialOfferStage = :stage where u.telegramId = :telegramId")
    void markTrialOffer(@Param("telegramId") Long telegramId, @Param("stage") int stage);

    @Modifying
    @Transactional
    @Query("update Vpn2User u set u.tariffNudgedAt = :at where u.telegramId = :telegramId")
    void markTariffNudged(@Param("telegramId") Long telegramId, @Param("at") LocalDateTime at);
}
