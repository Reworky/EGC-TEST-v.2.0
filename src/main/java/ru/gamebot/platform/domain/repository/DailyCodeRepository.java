package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.DailyCode;

public interface DailyCodeRepository extends JpaRepository<DailyCode, Long> {

    Optional<DailyCode> findFirstByValidUntilAfterOrderByIdDesc(LocalDateTime now);

    Optional<DailyCode> findFirstByOrderByIdDesc();

    /** Последние коды (для серии «кодов подряд»). */
    List<DailyCode> findTop30ByOrderByIdDesc();

    long countByPublishedAtGreaterThanEqualAndPublishedAtLessThan(LocalDateTime from, LocalDateTime to);
}
