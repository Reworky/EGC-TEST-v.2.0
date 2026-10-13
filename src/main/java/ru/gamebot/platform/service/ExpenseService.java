package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.ExpenseEntry;
import ru.gamebot.platform.domain.model.RecurringExpense;
import ru.gamebot.platform.domain.repository.ExpenseEntryRepository;
import ru.gamebot.platform.domain.repository.RecurringExpenseRepository;

/** Ручной учёт расходов проекта (финансовая сводка) и регулярные расходы, которые записываются сами раз в месяц. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpenseService {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private final ExpenseEntryRepository repository;
    private final RecurringExpenseRepository recurringRepository;

    @Transactional
    public ExpenseEntry add(String kind, long amountRub, LocalDate date, String note) {
        ExpenseEntry e = new ExpenseEntry();
        e.setKind(ExpenseEntry.KINDS.contains(kind) ? kind : ExpenseEntry.OTHER);
        e.setAmountRub(amountRub);
        e.setEntryDate(date);
        e.setNote(note == null || note.isBlank() ? null : (note.length() > 250 ? note.substring(0, 250) : note));
        return repository.save(e);
    }

    public void delete(Long id) {
        repository.deleteById(id);
    }

    public List<ExpenseEntry> all() {
        return repository.findAll();
    }

    public List<ExpenseEntry> between(LocalDate from, LocalDate to) {
        return repository.findAllByEntryDateBetweenOrderByEntryDateDesc(from, to);
    }

    /** Суммы по статьям за период (границы включительно), порядок статей как в меню; пустые статьи тоже есть (0). */
    public Map<String, Long> sumsByKind(LocalDate from, LocalDate to) {
        Map<String, Long> sums = new LinkedHashMap<>();
        for (String k : ExpenseEntry.KINDS) sums.put(k, 0L);
        for (ExpenseEntry e : between(from, to)) {
            String k = ExpenseEntry.KINDS.contains(e.getKind()) ? e.getKind() : ExpenseEntry.OTHER;
            sums.merge(k, e.getAmountRub(), Long::sum);
        }
        return sums;
    }

    public long total(LocalDate from, LocalDate to) {
        return sumsByKind(from, to).values().stream().mapToLong(Long::longValue).sum();
    }

    // ── Регулярные расходы ─────────────────────────────────────────────────────────────────

    public List<RecurringExpense> recurring() {
        return recurringRepository.findAllByOrderByDayOfMonthAscIdAsc();
    }

    /** Если день этого месяца уже прошёл, запись за текущий месяц НЕ делается (первая запись — со следующего месяца),
     *  иначе включение задним числом дописало бы расход, который админ мог внести вручную. */
    @Transactional
    public RecurringExpense addRecurring(String kind, long amountRub, int dayOfMonth, String note) {
        RecurringExpense r = new RecurringExpense();
        r.setKind(ExpenseEntry.KINDS.contains(kind) ? kind : ExpenseEntry.OTHER);
        r.setAmountRub(amountRub);
        r.setDayOfMonth(Math.max(1, Math.min(28, dayOfMonth)));
        r.setNote(note == null || note.isBlank() ? null : (note.length() > 250 ? note.substring(0, 250) : note));
        LocalDate today = LocalDate.now(MSK);
        if (today.getDayOfMonth() > r.getDayOfMonth()) {
            r.setLastPostedMonth(YearMonth.from(today).toString());
        }
        return recurringRepository.save(r);
    }

    public void deleteRecurring(Long id) {
        recurringRepository.deleteById(id);
    }

    /** Записывает наступившие регулярные расходы (не больше одного раза в месяц на правило); вызывается планировщиком раз в сутки.
     *  Бэклога нет: пропущенные при простое месяцы не дописываются, пишется только текущий месяц. */
    @Transactional
    public List<ExpenseEntry> postDueRecurring() {
        LocalDate today = LocalDate.now(MSK);
        String month = YearMonth.from(today).toString();
        List<ExpenseEntry> posted = new ArrayList<>();
        for (RecurringExpense r : recurringRepository.findAll()) {
            if (month.equals(r.getLastPostedMonth()) || today.getDayOfMonth() < r.getDayOfMonth()) continue;
            String note = (r.getNote() == null ? "" : r.getNote() + " ") + "(регулярный)";
            posted.add(add(r.getKind(), r.getAmountRub(), today.withDayOfMonth(r.getDayOfMonth()), note));
            r.setLastPostedMonth(month);
            recurringRepository.save(r);
        }
        return posted;
    }
}
