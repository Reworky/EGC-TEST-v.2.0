package ru.gamebot.platform.domain.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.gamebot.platform.domain.model.DailyBonus;

public interface DailyBonusRepository extends JpaRepository<DailyBonus, Long> {

    Optional<DailyBonus> findFirstByBonusDateOrderByIdDesc(LocalDate bonusDate);

    Optional<DailyBonus> findFirstByOrderByIdDesc();

    /** Строка блокируется на время резерва/возврата бюджета: два параллельных вывода не должны вместе превысить бюджет дня. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM DailyBonus b WHERE b.id = :id")
    Optional<DailyBonus> findByIdForUpdate(@Param("id") Long id);
}
