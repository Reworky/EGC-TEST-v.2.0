package ru.gamebot.platform.domain.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.DailyBonus;

public interface DailyBonusRepository extends JpaRepository<DailyBonus, Long> {

    Optional<DailyBonus> findFirstByBonusDateOrderByIdDesc(LocalDate bonusDate);

    Optional<DailyBonus> findFirstByOrderByIdDesc();

    /** Строка блокируется на время резерва/возврата бюджета: два параллельных вывода не должны вместе превысить бюджет дня. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM DailyBonus b WHERE b.id = :id")
    Optional<DailyBonus> findByIdForUpdate(@Param("id") Long id);

    /** Счётчик нажатий на кнопку под постом: точечный UPDATE, чтобы параллельные нажатия не затирали друг друга. */
    @Transactional
    @Modifying
    @Query("UPDATE DailyBonus b SET b.clicks = b.clicks + 1 WHERE b.id = :id")
    int addClick(@Param("id") Long id);

    /** Занять место красной кнопки: атомарно, пока slotsUsed < slots. 0 - мест не осталось (параллельные нажатия не обгоняют лимит). */
    @Transactional
    @Modifying
    @Query("UPDATE DailyBonus b SET b.slotsUsed = b.slotsUsed + 1 WHERE b.id = :id AND b.slotsUsed < b.slots")
    int takeSlot(@Param("id") Long id);

    /** Вернуть место, если активация после его занятия не удалась (двойное нажатие, сбой записи). */
    @Transactional
    @Modifying
    @Query("UPDATE DailyBonus b SET b.slotsUsed = b.slotsUsed - 1 WHERE b.id = :id AND b.slotsUsed > 0")
    int releaseSlot(@Param("id") Long id);

    /** +1 подходящему игроку, нажавшему красную кнопку (получил место или не хватило). */
    @Transactional
    @Modifying
    @Query("UPDATE DailyBonus b SET b.attempts = b.attempts + 1 WHERE b.id = :id")
    int addAttempt(@Param("id") Long id);

    /** Последние красные кнопки, на которых были подходящие нажавшие: по ним считается ожидаемая аудитория. */
    java.util.List<DailyBonus> findTop3ByTypeAndAttemptsGreaterThanOrderByIdDesc(String type, int attempts);
}
