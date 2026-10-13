package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.FinanceEntry;
import ru.gamebot.platform.domain.repository.FinanceEntryRepository;

/** Ручной учёт доходов проекта (финансовая сводка): записи вводит админ, выплаты пользователям берутся из платформы. */
@Service
@RequiredArgsConstructor
public class FinanceService {

    private final FinanceEntryRepository repository;

    public FinanceEntry add(String kind, long amountRub, LocalDate date, String note) {
        FinanceEntry e = new FinanceEntry();
        e.setKind(kind);
        e.setAmountRub(amountRub);
        e.setEntryDate(date);
        e.setNote(note == null || note.isBlank() ? null : (note.length() > 250 ? note.substring(0, 250) : note));
        return repository.save(e);
    }

    public void delete(Long id) {
        repository.deleteById(id);
    }

    public List<FinanceEntry> between(LocalDate from, LocalDate to) {
        return repository.findAllByEntryDateBetweenOrderByEntryDateDesc(from, to);
    }

    /** История всех записанных доходов (новые сверху), страница с нуля. */
    public Page<FinanceEntry> history(int page, int size) {
        return repository.findAllByOrderByEntryDateDescIdDesc(PageRequest.of(Math.max(0, page), size));
    }

    /** Итоги за всё время по видам: [вид, число записей, сумма ₽]. */
    public List<Object[]> totalsByKind() {
        return repository.totalsByKind();
    }

    public List<FinanceEntry> all() {
        return repository.findAll();
    }

    public List<FinanceEntry> latest() {
        return repository.findTop10ByOrderByCreatedAtDesc();
    }

    /** Сумма дохода по виду за период (границы включительно). */
    public long sumByKind(String kind, LocalDate from, LocalDate to) {
        long sum = 0;
        for (FinanceEntry e : between(from, to)) {
            if (kind.equals(e.getKind())) sum += e.getAmountRub();
        }
        return sum;
    }
}
