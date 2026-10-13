package ru.gamebot.platform.domain.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.gamebot.platform.domain.model.ExpenseEntry;

public interface ExpenseEntryRepository extends JpaRepository<ExpenseEntry, Long> {

    /** Границы включительно (даты записей - календарные). */
    List<ExpenseEntry> findAllByEntryDateBetweenOrderByEntryDateDesc(LocalDate from, LocalDate to);
}
