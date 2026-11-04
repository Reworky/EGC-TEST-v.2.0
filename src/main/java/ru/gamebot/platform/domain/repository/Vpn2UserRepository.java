package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.Vpn2User;

public interface Vpn2UserRepository extends JpaRepository<Vpn2User, Long> {

    Optional<Vpn2User> findByTelegramId(Long telegramId);

    long countByCreatedAtAfter(LocalDateTime after);

    long countByReferredByIsNotNull();

    long countByReferralRewardedTrue();
}
