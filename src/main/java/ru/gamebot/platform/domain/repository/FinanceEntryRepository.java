package ru.gamebot.platform.domain.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.gamebot.platform.domain.model.FinanceEntry;

public interface FinanceEntryRepository extends JpaRepository<FinanceEntry, Long> {

    /** Границы включительно (даты записей - календарные). */
    List<FinanceEntry> findAllByEntryDateBetweenOrderByEntryDateDesc(LocalDate from, LocalDate to);

    List<FinanceEntry> findTop10ByOrderByCreatedAtDesc();

    /** Вся история доходов постранично: новые даты сверху. */
    Page<FinanceEntry> findAllByOrderByEntryDateDescIdDesc(Pageable pageable);

    /** Итоги за всё время по видам: [вид, число записей, сумма ₽]. */
    @Query("SELECT e.kind, COUNT(e), COALESCE(SUM(e.amountRub), 0) FROM FinanceEntry e GROUP BY e.kind")
    List<Object[]> totalsByKind();
}
