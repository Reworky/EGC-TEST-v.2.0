package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.model.NotificationLog;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    long countByUserIdAndSentAtAfter(Long userId, LocalDateTime since);

    Optional<NotificationLog> findFirstByUserIdOrderBySentAtDesc(Long userId);

    /** По типам за период: [type, всего отправлено, из них старше matureBefore, из них вернулись]. «Вернулись» считаем только
     * среди «созревших» сообщений (старше 48 ч), иначе свежие занижали бы процент. */
    @Query("select n.type, count(n), "
            + "sum(case when n.sentAt < :matureBefore then 1 else 0 end), "
            + "sum(case when n.sentAt < :matureBefore and n.returnedAt is not null then 1 else 0 end) "
            + "from NotificationLog n where n.sentAt > :since group by n.type")
    List<Object[]> reportByType(@Param("since") LocalDateTime since, @Param("matureBefore") LocalDateTime matureBefore);
}
