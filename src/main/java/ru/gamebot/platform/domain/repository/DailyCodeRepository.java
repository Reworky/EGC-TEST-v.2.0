package ru.gamebot.platform.domain.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.DailyCode;

public interface DailyCodeRepository extends JpaRepository<DailyCode, Long> {

    Optional<DailyCode> findFirstByValidUntilAfterOrderByIdDesc(LocalDateTime now);

    Optional<DailyCode> findFirstByOrderByIdDesc();
}
