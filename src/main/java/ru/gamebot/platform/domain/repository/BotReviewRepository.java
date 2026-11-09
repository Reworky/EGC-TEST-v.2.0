package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.enums.BotReviewStatus;
import ru.gamebot.platform.domain.model.BotReview;

public interface BotReviewRepository extends JpaRepository<BotReview, Long> {

    /** С подгруженным user — иначе review.getUser().getNickname() в publishReviewToChannel
     * кидает LazyInitializationException вне активной Hibernate-сессии (баг, найденный 2026-09-02:
     * отзыв тихо помечался PUBLISHED, но публикация в канал молча падала без логирования, т.к.
     * LazyInitializationException — не TelegramApiException). */
    @EntityGraph(attributePaths = {"user"})
    Optional<BotReview> findWithUserById(Long id);

    /** Отзывы для обобщённого недельного отчёта (см. WeeklyResetScheduler.postWeeklyReviewSummary,
     * 2026-09-14 — по явному запросу заменили репост ОДНОГО отзыва на обобщённую сводку за неделю,
     * приуроченную к тому, что админ сам готовит недельный итог по понедельникам). Сначала более
     * высокая оценка, затем свежее — чтобы лучшие отзывы недели попадали в цитаты первыми. */
    @EntityGraph(attributePaths = {"user"})
    List<BotReview> findAllByStatusAndCreatedAtAfterOrderByStarsDescCreatedAtDesc(BotReviewStatus status, LocalDateTime after);

    /** Опубликованные отзывы с высокой оценкой для публичного блока на сайте (свежие первыми). */
    @EntityGraph(attributePaths = {"user"})
    List<BotReview> findByStatusAndStarsGreaterThanEqualOrderByCreatedAtDesc(BotReviewStatus status, int stars, org.springframework.data.domain.Pageable pageable);

    /** То же, но только отзывы с текстом не короче minLen символов, отбор прямо в запросе (свежие первыми).
     *  Раньше сайт брал 40 последних отзывов и уже потом отсеивал пустые: звёзды без текста составляют большинство, и из 190 опубликованных
     *  на сайт попадали 2 (2026-11-09), хотя отзывов с текстом было 31. */
    @EntityGraph(attributePaths = {"user"})
    @Query("SELECT r FROM BotReview r WHERE r.status = :status AND r.stars >= :stars AND r.text IS NOT NULL "
            + "AND LENGTH(TRIM(r.text)) >= :minLen ORDER BY r.createdAt DESC")
    List<BotReview> findPublicWithText(@Param("status") BotReviewStatus status, @Param("stars") int stars,
                                       @Param("minLen") int minLen, org.springframework.data.domain.Pageable pageable);
}
