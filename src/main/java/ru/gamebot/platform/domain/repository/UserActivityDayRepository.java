package ru.gamebot.platform.domain.repository;

import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.UserActivityDay;

public interface UserActivityDayRepository extends JpaRepository<UserActivityDay, Long> {

    boolean existsByUserIdAndDay(Long userId, LocalDate day);

    long countByDay(LocalDate day);
}
