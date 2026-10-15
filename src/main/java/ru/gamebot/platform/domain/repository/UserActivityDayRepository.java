package ru.gamebot.platform.domain.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.gamebot.platform.domain.model.UserActivityDay;

public interface UserActivityDayRepository extends JpaRepository<UserActivityDay, Long> {

    boolean existsByUserIdAndActivityDay(Long userId, LocalDate activityDay);

    long countByActivityDay(LocalDate activityDay);

    /** Первый день, с которого ведётся журнал (null - журнал пуст). */
    @Query("SELECT MIN(d.activityDay) FROM UserActivityDay d")
    LocalDate findFirstDay();

    /** Все пары «игрок - активный день» для расчёта когорт (RetentionService). */
    @Query("SELECT d.userId, d.activityDay FROM UserActivityDay d")
    List<Object[]> findAllPairs();
}
