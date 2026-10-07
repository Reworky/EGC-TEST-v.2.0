package ru.gamebot.platform.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.FeatureInterest;

public interface FeatureInterestRepository extends JpaRepository<FeatureInterest, Long> {

    boolean existsByTelegramIdAndFeatureCode(Long telegramId, String featureCode);

    long countByFeatureCode(String featureCode);
}
