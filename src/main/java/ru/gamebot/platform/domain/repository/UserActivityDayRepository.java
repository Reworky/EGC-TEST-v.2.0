package ru.gamebot.platform.domain.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.gamebot.platform.domain.model.UserActivityDay;

public interface UserActivityDayRepository extends JpaRepository<UserActivityDay, Long> {

    boolean existsByUserIdAndDay(Long userId, LocalDate day);

    long countByDay(LocalDate day);

    /** Первый день, с которого ведётся журнал (null - журнал пуст). */
    @Query("SELECT MIN(d.day) FROM UserActivityDay d")
    LocalDate findFirstDay();

    /** Все пары «игрок - активный день» для расчёта когорт (RetentionService). */
    @Query("SELECT d.userId, d.day FROM UserActivityDay d")
    List<Object[]> findAllPairs();
}
