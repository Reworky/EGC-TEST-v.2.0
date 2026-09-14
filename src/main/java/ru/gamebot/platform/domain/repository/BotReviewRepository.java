package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
