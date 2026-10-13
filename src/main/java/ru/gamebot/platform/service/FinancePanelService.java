package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.ExpenseEntry;
import ru.gamebot.platform.domain.model.FinanceEntry;

/** Данные единой панели «Финансы»: P&L за период (доходы по статьям, расходы по статьям, выплаты игрокам) и общий журнал записей. */
@Service
@RequiredArgsConstructor
public class FinancePanelService {

    private static final List<String> INCOME_KINDS = List.of(FinanceEntry.SPONSOR, FinanceEntry.DIRECT_ADS,
            FinanceEntry.YANDEX_RSYA, FinanceEntry.TELEGA, FinanceEntry.OTHER);

    private final FinanceService financeService;
    private final ExpenseService expenseService;
    private final AnalyticsService analyticsService;

    public record Report(LocalDate from, LocalDate to, Map<String, Long> income, Map<String, Long> expenses, long payouts) {
        public long incomeTotal() { return income.values().stream().mapToLong(Long::longValue).sum(); }
        public long expensesTotal() { return expenses.values().stream().mapToLong(Long::longValue).sum(); }
        public long net() { return incomeTotal() - payouts - expensesTotal(); }
        /** Маржа в процентах от доходов; null, если доходов нет. */
        public Integer marginPercent() {
            long inc = incomeTotal();
            return inc > 0 ? (int) Math.round(net() * 100.0 / inc) : null;
        }
    }

    /** Строка общего журнала: income=true - доход, иначе расход. */
    public record JournalItem(boolean income, Long id, LocalDate date, String kind, long amountRub, String note, long createdKey) {
        public String label() {
            return income ? FinanceEntry.kindLabel(kind) : ExpenseEntry.kindLabel(kind);
        }
    }

    public Report report(LocalDate from, LocalDate to) {
        Map<String, Long> income = new LinkedHashMap<>();
        for (String k : INCOME_KINDS) income.put(k, financeService.sumByKind(k, from, to));
        return new Report(from, to, income, expenseService.sumsByKind(from, to), analyticsService.payoutsRub(from, to));
    }

    /** Все записи доходов и расходов, новые сверху (по дате, затем по порядку создания). */
    public List<JournalItem> journal() {
        List<JournalItem> items = new ArrayList<>();
        for (FinanceEntry e : financeService.all()) {
            items.add(new JournalItem(true, e.getId(), e.getEntryDate(), e.getKind(), e.getAmountRub(), e.getNote(), e.getId()));
        }
        for (ExpenseEntry e : expenseService.all()) {
            items.add(new JournalItem(false, e.getId(), e.getEntryDate(), e.getKind(), e.getAmountRub(), e.getNote(), e.getId()));
        }
        items.sort(Comparator.comparing(JournalItem::date).reversed()
                .thenComparing(Comparator.comparingLong(JournalItem::createdKey).reversed()));
        return items;
    }
}
