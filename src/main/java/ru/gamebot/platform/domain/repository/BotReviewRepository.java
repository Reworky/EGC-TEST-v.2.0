package ru.gamebot.platform.domain.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.BotReview;

public interface BotReviewRepository extends JpaRepository<BotReview, Long> {

    /** С подгруженным user — иначе review.getUser().getNickname() в publishReviewToChannel
     * кидает LazyInitializationException вне активной Hibernate-сессии (баг, найденный 2026-09-02:
     * отзыв тихо помечался PUBLISHED, но публикация в канал молча падала без логирования, т.к.
     * LazyInitializationException — не TelegramApiException). */
    @EntityGraph(attributePaths = {"user"})
    Optional<BotReview> findWithUserById(Long id);
}
