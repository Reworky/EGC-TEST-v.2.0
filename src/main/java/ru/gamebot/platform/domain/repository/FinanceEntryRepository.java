package ru.gamebot.platform.domain.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.FinanceEntry;

public interface FinanceEntryRepository extends JpaRepository<FinanceEntry, Long> {

    /** Границы включительно (даты записей - календарные). */
    List<FinanceEntry> findAllByEntryDateBetweenOrderByEntryDateDesc(LocalDate from, LocalDate to);

    List<FinanceEntry> findTop10ByOrderByCreatedAtDesc();
}
