package ru.gamebot.platform.domain.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import ru.gamebot.platform.domain.model.BotReview;

public interface BotReviewRepository extends JpaRepository<BotReview, Long> {

    /** С подгруженным user — иначе review.getUser().getNickname() в publishReviewToChannel
     * кидает LazyInitializationException вне активной Hibernate-сессии (баг, найденный 2026-09-02:
     * отзыв тихо помечался PUBLISHED, но публикация в канал молча падала без логирования, т.к.
     * LazyInitializationException — не TelegramApiException). */
    @EntityGraph(attributePaths = {"user"})
    Optional<BotReview> findWithUserById(Long id);

    /** Кандидаты на еженедельный репост в основной канал — уже опубликованные в @egc_payouts (есть
     * messageId), с высоким рейтингом, ещё не предлагавшиеся. Сортировка — сначала выше рейтинг,
     * затем свежее, берём один через Pageable.ofSize(1) (см. onReviewRepostCandidate). */
    @EntityGraph(attributePaths = {"user"})
    @Query("SELECT r FROM BotReview r WHERE r.status = 'PUBLISHED' AND r.publishedMessageId IS NOT NULL "
            + "AND r.repostedToMainChannel = false AND r.stars >= 4 "
            + "ORDER BY r.stars DESC, r.createdAt DESC")
    List<BotReview> findRepostCandidates(Pageable pageable);
}
