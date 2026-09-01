package ru.gamebot.platform.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.BotReview;

public interface BotReviewRepository extends JpaRepository<BotReview, Long> {
}
