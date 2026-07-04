package ru.gamebot.platform.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.Season;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SeasonRepository extends JpaRepository<Season, Long> {
    List<Season> findAllByOrderByCreatedAtDesc();
    Optional<Season> findFirstByActiveTrueAndStartDateBeforeAndEndDateAfterOrderByCreatedAtDesc(
            LocalDateTime now1, LocalDateTime now2);
}
