package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.FinanceEntry;
import ru.gamebot.platform.domain.model.HealthRatioSnapshot;
import ru.gamebot.platform.domain.model.PlatformSnapshot;
import ru.gamebot.platform.domain.model.PulseSnapshotEntry;
import ru.gamebot.platform.domain.model.PulseThresholdLog;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.HealthRatioSnapshotRepository;
import ru.gamebot.platform.domain.repository.PlatformSnapshotRepository;
import ru.gamebot.platform.domain.repository.PulseSnapshotEntryRepository;
import ru.gamebot.platform.domain.repository.PulseThresholdLogRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.AnalyticsAlertEvent;

/** «Пульс проекта» (ТЗ владельца от 29.10.2026): пять сигналов с автоматическим статусом (зелёный, жёлтый, красный, серый).
 *  Считается на момент запроса; раз в сутки (03:00) результат пишется в pulse_snapshot_entries - это история для «значение неделей раньше»,
 *  правила «красный только со второго нарушения подряд» и выгрузки в CSV. Пороги лежат в app_settings и меняются без выпуска версии. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PulseService {

    public enum Status {
        GREEN("green", "🟢", "зелёный"), YELLOW("yellow", "🟡", "жёлтый"), RED("red", "🔴", "красный"),
        GREY("grey", "⚪", "серый"), NONE("none", "▫️", "без статуса");

        public final String code;
        public final String icon;
        public final String title;

        Status(String code, String icon, String title) {
            this.code = code;
            this.icon = icon;
            this.title = title;
        }

        static Status ofCode(String code) {
            for (Status s : values()) if (s.code.equals(code)) return s;
            return GREY;
        }

        /** Чем больше, тем хуже: красный > жёлтый > зелёный. Серый и «без статуса» в расчёт сигнала не входят. */
        int severity() {
            return switch (this) {
                case RED -> 3;
                case YELLOW -> 2;
                case GREEN -> 1;
                default -> 0;
            };
        }
    }

    /** Определение метрики: green/red = границы зелёного и красного (null у red - метрика без статуса). higherIsBetter: рост - хорошо. */
    public record Def(String key, int signal, String label, String unit, boolean higherIsBetter, Double green, Double red, String basis) {}

    public static final String[] SIGNAL_TITLES = {
            "", "Ядро активных игроков", "Возврат новичков", "Зависимость от всплесков притока", "Экономика", "Цена обслуживания"};

    public static final List<Def> DEFS = List.of(
            new Def("core30", 1, "Ядро: заходили за 30 дн.", "", true, -5.0, -15.0, "изменение за 7 дн., %"),
            new Def("core_dau_mau", 1, "Ядро: DAU/MAU", "%", true, 25.0, 15.0, "%"),
            new Def("d1", 2, "D1 последней зрелой недели", "%", true, 40.0, 30.0, "%"),
            new Def("d7", 2, "D7 последней зрелой недели", "%", true, 15.0, 10.0, "%"),
            new Def("d30", 2, "D30 (без статуса)", "%", true, null, null, ""),
            new Def("residual", 3, "Остаточная активность", "%", true, 85.0, 65.0, "%"),
            new Def("cac", 3, "Стоимость новичка", "₽", false, null, null, ""),
            new Def("course", 4, "Курс EXC (₽ за 1000 EXC)", "₽", true, -5.0, -20.0, "изменение за 30 дн., %"),
            new Def("runway", 4, "Запас хода", "мес.", true, 12.0, 6.0, "мес."),
            new Def("coverage", 4, "Покрытие: доходы ÷ выплаты за 7 дн. (без статуса)", "%", true, null, null, ""),
            new Def("review_h", 5, "Время проверки заявок", "ч", false, 24.0, 48.0, "ч"),
            new Def("fraud", 5, "Доля фрода", "%", false, 5.0, 10.0, "%"),
            new Def("warns", 5, "Предупреждения в логах за 24 ч", "", false, 20.0, 100.0, "шт."));

    private static final int MIN_COHORT = 50;
    private static final int CORE_MIN_QUESTS = 3;
    private static final int CORE_MIN_AGE_DAYS = 14;
    private static final String SETTING_PREFIX = "pulse.th.";
    private static final DateTimeFormatter DM = DateTimeFormatter.ofPattern("dd.MM");
    private static final Locale RU = Locale.forLanguageTag("ru");

    /** Результат по одной метрике. raw - по порогам, shown - с правилом «два отчёта подряд». */
    public record Metric(Def def, Double value, Double prev, Status raw, Status shown, String note) {}

    public record SignalResult(int no, String title, Status status, List<Metric> metrics) {}

    public record Result(LocalDateTime at, Status project, int greens, int yellows, int reds, List<SignalResult> signals) {}

    private final AppUserRepository userRepo;
    private final UserService userService;
    private final RetentionService retentionService;
    private final RewardService rewardService;
    private final HealthRatioService healthRatioService;
    private final HealthRatioSnapshotRepository ratioSnapRepo;
    private final FinanceService financeService;
    private final QuestSubmissionRepository submissionRepo;
    private final ErrorMonitorService errorMonitor;
    private final PlatformSnapshotRepository platformSnapRepo;
    private final AppSettingRepository settingRepo;
    private final PulseSnapshotEntryRepository entryRepo;
    private final PulseThresholdLogRepository thresholdLogRepo;
    private final ApplicationEventPublisher eventPublisher;

    // ───────────────────────── пороги ─────────────────────────

    public static Def defOf(String key) {
        for (Def d : DEFS) if (d.key().equals(key)) return d;
        return null;
    }

    /** {граница зелёного, граница красного}; null - у метрики нет порогов. */
    public double[] thresholdsOf(Def d) {
        if (d.red() == null) return null;
        Optional<AppSetting> s = settingRepo.findById(SETTING_PREFIX + d.key());
        if (s.isPresent() && s.get().getValue() != null) {
            try {
                String[] p = s.get().getValue().split(";");
                return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])};
            } catch (Exception e) {
                log.warn("Pulse: bad threshold setting for {}: {}", d.key(), s.get().getValue());
            }
        }
        return new double[]{d.green(), d.red()};
    }

    private static String pair(double g, double r) {
        return fmt(g) + ";" + fmt(r);
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** Меняет пороги метрики и пишет в журнал. Возвращает текст ошибки или null. */
    public String setThreshold(String key, double green, double red, Long byTelegramId) {
        Def d = defOf(key);
        if (d == null) return "Нет такой метрики: " + key;
        if (d.red() == null) return "У метрики «" + d.label() + "» нет порогов.";
        if (d.higherIsBetter() && green < red) return "Для этой метрики зелёная граница должна быть не ниже красной (зелёный " + fmt(green) + ", красный " + fmt(red) + ").";
        if (!d.higherIsBetter() && green > red) return "Для этой метрики зелёная граница должна быть не выше красной (зелёный " + fmt(green) + ", красный " + fmt(red) + ").";
        double[] old = thresholdsOf(d);
        saveSetting(d.key(), pair(green, red));
        logChange(d.key(), pair(old[0], old[1]), pair(green, red), byTelegramId);
        return null;
    }

    /** Возвращает стартовые значения всех порогов. */
    public void resetThresholds(Long byTelegramId) {
        for (Def d : DEFS) {
            if (d.red() == null) continue;
            double[] cur = thresholdsOf(d);
            if (cur[0] == d.green() && cur[1] == d.red()) continue;
            saveSetting(d.key(), pair(d.green(), d.red()));
            logChange(d.key(), pair(cur[0], cur[1]), pair(d.green(), d.red()), byTelegramId);
        }
    }

    private void saveSetting(String key, String value) {
        AppSetting s = settingRepo.findById(SETTING_PREFIX + key).orElseGet(() -> {
            AppSetting n = new AppSetting();
            n.setKey(SETTING_PREFIX + key);
            return n;
        });
        s.setValue(value);
        settingRepo.save(s);
    }

    private void logChange(String key, String oldV, String newV, Long by) {
        PulseThresholdLog l = new PulseThresholdLog();
        l.setMetricKey(key);
        l.setOldValues(oldV);
        l.setNewValues(newV);
        l.setChangedByTelegramId(by);
        thresholdLogRepo.save(l);
    }

    /** Текст для админа: ключи, текущие границы и последние изменения. */
    public String thresholdsText() {
        StringBuilder sb = new StringBuilder("⚙️ <b>Пороги пульса</b>\n\nМетрика (ключ): граница зелёного / граница красного\n\n");
        for (Def d : DEFS) {
            if (d.red() == null) continue;
            double[] t = thresholdsOf(d);
            sb.append(d.signal()).append(". ").append(d.label()).append(" (<code>").append(d.key()).append("</code>): ")
              .append(d.higherIsBetter() ? "зелёный от " : "зелёный до ").append(fmt(t[0]))
              .append(d.higherIsBetter() ? ", красный ниже " : ", красный выше ").append(fmt(t[1])).append(" · ").append(d.basis()).append("\n");
        }
        sb.append("\nИзменить: <code>/pulsethr ключ зелёный красный</code>, например <code>/pulsethr d1 40 30</code>\n"
                + "Вернуть стартовые: <code>/pulsethr reset</code>. Журнал: <code>/pulsethr log</code>.");
        return sb.toString();
    }

    public String thresholdLogText() {
        List<PulseThresholdLog> list = thresholdLogRepo.findTop20ByOrderByChangedAtDesc();
        if (list.isEmpty()) return "Журнал порогов пуст: значения стартовые.";
        StringBuilder sb = new StringBuilder("📜 <b>Журнал порогов пульса</b> (последние 20)\n\n");
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        for (PulseThresholdLog l : list) {
            Def d = defOf(l.getMetricKey());
            sb.append(l.getChangedAt().format(f)).append(" · ").append(d == null ? l.getMetricKey() : d.label()).append(": ")
              .append(l.getOldValues()).append(" → <b>").append(l.getNewValues()).append("</b> · ")
              .append(l.getChangedByTelegramId() == null ? "—" : "<code>" + l.getChangedByTelegramId() + "</code>").append("\n");
        }
        return sb.toString();
    }

    // ───────────────────────── расчёт ─────────────────────────

    /** Считает все сигналы на текущий момент. Ничего не пишет в базу. */
    public Result compute() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = LocalDate.now();
        List<Metric> all = new ArrayList<>();
        for (Def d : DEFS) {
            try {
                all.add(computeOne(d, now, today));
            } catch (Exception e) {
                log.warn("Pulse: metric {} failed", d.key(), e);
                all.add(finish(d, null, null, null, "не удалось посчитать, подробности в логе", today));
            }
        }
        List<SignalResult> signals = new ArrayList<>();
        int greens = 0, yellows = 0, reds = 0;
        for (int no = 1; no < SIGNAL_TITLES.length; no++) {
            List<Metric> ms = new ArrayList<>();
            for (Metric m : all) if (m.def().signal() == no) ms.add(m);
            Status st = signalStatus(ms);
            signals.add(new SignalResult(no, SIGNAL_TITLES[no], st, ms));
            if (st == Status.GREEN) greens++;
            else if (st == Status.YELLOW) yellows++;
            else if (st == Status.RED) reds++;
        }
        Status project;
        if (reds >= 2) project = Status.RED;
        else if (reds >= 1 || yellows >= 1) project = Status.YELLOW;
        else if (greens >= 1) project = Status.GREEN;
        else project = Status.GREY;
        return new Result(now, project, greens, yellows, reds, signals);
    }

    private static Status signalStatus(List<Metric> ms) {
        Status worst = Status.GREY;
        int sev = 0;
        for (Metric m : ms) {
            if (m.shown().severity() > sev) {
                sev = m.shown().severity();
                worst = m.shown();
            }
        }
        return worst;
    }

    private Metric computeOne(Def d, LocalDateTime now, LocalDate today) {
        switch (d.key()) {
            case "core30": {
                LocalDateTime maxCreated = now.minusDays(CORE_MIN_AGE_DAYS);
                double cur = userRepo.countCoreActiveSince(now.minusDays(30), CORE_MIN_QUESTS, maxCreated);
                Double prev = historyValue("core30", today.minusDays(7), today);
                if (prev == null || prev <= 0) {
                    return finish(d, cur, null, null, "нет базы сравнения: значение 7 суток назад появится после недели ежедневных снимков", today);
                }
                double change = (cur - prev) * 100.0 / prev;
                return finish(d, cur, prev, change, "изменение за 7 дн.: " + signed(change) + "%", today);
            }
            case "core_dau_mau": {
                LocalDateTime maxCreated = now.minusDays(CORE_MIN_AGE_DAYS);
                double dau = userRepo.countCoreActiveSince(now.minusDays(1), CORE_MIN_QUESTS, maxCreated);
                double mau = userRepo.countCoreActiveSince(now.minusDays(30), CORE_MIN_QUESTS, maxCreated);
                if (mau <= 0) return finish(d, null, null, null, "в ядре нет активных за 30 дн.", today);
                double v = dau * 100.0 / mau;
                return finish(d, v, historyValue("core_dau_mau", today.minusDays(7), today), v, "в ядре заходили за 24 ч: " + (long) dau + " из " + (long) mau, today);
            }
            case "d1":
                return retentionMetric(d, 1, today);
            case "d7":
                return retentionMetric(d, 7, today);
            case "d30":
                return retentionMetric(d, 30, today);
            case "residual": {
                double cur = userRepo.countActiveSince(today.minusDays(7));
                List<PlatformSnapshot> snaps = platformSnapRepo.findAllBySnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(today.minusDays(14));
                double max = cur;
                for (PlatformSnapshot s : snaps) max = Math.max(max, s.getActive7Days());
                if (snaps.isEmpty() || max <= 0) return finish(d, null, null, null, "нет ежедневных снимков за 14 дней", today);
                double v = cur * 100.0 / max;
                return finish(d, v, historyValue("residual", today.minusDays(7), today), v,
                        "активных за 7 дн. сейчас " + (long) cur + " из максимума " + (long) max + " за 14 дн.", today);
            }
            case "cac":
                return finish(d, null, null, null, "нет данных: у платного притока нет меток источников, показываем только при наличии", today);
            case "course": {
                long pool = healthRatioService.getPayoutPoolRub();
                long coins = healthRatioService.getTotalDebtExc();
                if (coins <= 0) return finish(d, null, null, null, "на счетах игроков нет EXC", today);
                double cur = pool * 1000.0 / coins;
                Optional<HealthRatioSnapshot> old = ratioSnapRepo.findFirstByCalculatedAtLessThanEqualOrderByCalculatedAtDesc(now.minusDays(30));
                Double prev = old.filter(o -> o.getTotalDebtExc() > 0).map(o -> o.getPayoutPoolRub() * 1000.0 / o.getTotalDebtExc()).orElse(null);
                if (prev == null || prev <= 0) return finish(d, cur, null, null, "нет снимка курса 30 суток назад; пул " + pool + " ₽", today);
                double change = (cur - prev) * 100.0 / prev;
                return finish(d, cur, prev, change, "пул " + pool + " ₽ ÷ " + (coins / 1000) + " тыс. EXC; изменение за 30 дн.: " + signed(change) + "%", today);
            }
            case "runway": {
                long exc30 = 0;
                LocalDateTime t30 = now.minusDays(30);
                for (RewardService.WithdrawalRow r : rewardService.approvedWithdrawalRows()) {
                    if (!r.at().isBefore(t30)) exc30 += r.exc();
                }
                double burn = exc30 * healthRatioService.getCurrentRatio() / 100.0;
                if (burn <= 0) return finish(d, null, null, null, "за 30 дн. не было выплат", today);
                double v = healthRatioService.getPayoutPoolRub() / burn;
                return finish(d, v, historyValue("runway", today.minusDays(7), today), v, "пул ÷ выплаты за 30 дн. по текущему курсу", today);
            }
            case "coverage": {
                LocalDate from = today.minusDays(6);
                long income = financeService.sumByKind(FinanceEntry.DIRECT_ADS, from, today) + financeService.sumByKind(FinanceEntry.YANDEX_RSYA, from, today)
                        + financeService.sumByKind(FinanceEntry.TELEGA, from, today) + financeService.sumByKind(FinanceEntry.SPONSOR, from, today)
                        + financeService.sumByKind(FinanceEntry.OTHER, from, today);
                RewardService.WithdrawalPeriodStats w = rewardService.withdrawalStatsSince(now.minusDays(7));
                long payouts = w.totalRub() + w.totalTonRub();
                if (payouts <= 0) return finish(d, null, null, null, "за 7 дн. не было выплат в рублях", today);
                double v = income * 100.0 / payouts;
                return finish(d, v, historyValue("coverage", today.minusDays(7), today), null, "доходы " + income + " ₽ ÷ выплаты " + payouts + " ₽ (звёзды не входят)", today);
            }
            case "review_h": {
                List<Object[]> rows = submissionRepo.findReviewTimesBetween(now.minusDays(7), now);
                if (rows.isEmpty()) return finish(d, null, null, null, "за 7 дн. нет решений по заявкам", today);
                double sum = 0;
                for (Object[] r : rows) sum += Math.max(0, java.time.Duration.between((LocalDateTime) r[0], (LocalDateTime) r[1]).toMinutes());
                double hours = sum / rows.size() / 60.0;
                return finish(d, hours, historyValue("review_h", today.minusDays(7), today), hours, "среднее от отправки до решения, решений: " + rows.size(), today);
            }
            case "fraud": {
                long total = Math.max(1, userService.totalRegisteredUsers());
                long flagged = userRepo.countFraudOrBlocked();
                double v = flagged * 100.0 / total;
                return finish(d, v, historyValue("fraud", today.minusDays(7), today), v,
                        "подозрительных и заблокированных: " + flagged + " из " + total, today);
            }
            case "warns": {
                var sum = errorMonitor.summarize(24);
                double v = sum.warns();
                Metric m = finish(d, v, historyValue("warns", today.minusDays(7), today), v, "ERROR за 24 ч: " + sum.errors(), today);
                if (sum.errors() > 0 && m.shown() == Status.GREEN) {
                    return new Metric(d, m.value(), m.prev(), Status.YELLOW, Status.YELLOW, m.note() + " (любая ERROR даёт жёлтый)");
                }
                return m;
            }
            default:
                return finish(d, null, null, null, "метрика не реализована", today);
        }
    }

    /** D1/D7/D30: последняя зрелая недельная когорта; процент только при размере когорты от MIN_COHORT. prev - предыдущая зрелая неделя. */
    private Metric retentionMetric(Def d, int n, LocalDate today) {
        RetentionService.Report rep = retentionService.report();
        RetentionService.WeekRow cur = null, before = null;
        RetentionService.Rate curRate = null, beforeRate = null;
        for (RetentionService.WeekRow w : rep.weeks()) {
            RetentionService.Rate r = n == 1 ? w.d1() : n == 7 ? w.d7() : w.d30();
            if (!r.measurable()) continue;
            if (cur == null) { cur = w; curRate = r; }
            else { before = w; beforeRate = r; break; }
        }
        if (cur == null) return finish(d, null, null, null, "нет зрелых недельных когорт: день " + n + " ещё не прошёл у всех игроков недели", today);
        Double prev = before != null && beforeRate.n() >= MIN_COHORT ? beforeRate.returned() * 100.0 / beforeRate.n() : null;
        String week = "неделя с " + cur.weekStart().format(DM) + ", игроков " + curRate.n();
        if (curRate.n() < MIN_COHORT) return finish(d, null, prev, null, "мало данных: " + week + " (нужно от " + MIN_COHORT + ")", today);
        double v = curRate.returned() * 100.0 / curRate.n();
        return finish(d, v, prev, v, week + ", вернулись " + curRate.returned(), today);
    }

    /** Собирает метрику: статус по порогам от statusValue (null - серый с причиной в note), затем правило «красный со второго нарушения». */
    private Metric finish(Def d, Double value, Double prev, Double statusValue, String note, LocalDate today) {
        Status raw;
        if (d.red() == null) {
            raw = Status.NONE;
        } else if (statusValue == null) {
            raw = Status.GREY;
        } else {
            double[] t = thresholdsOf(d);
            if (d.higherIsBetter()) raw = statusValue >= t[0] ? Status.GREEN : statusValue < t[1] ? Status.RED : Status.YELLOW;
            else raw = statusValue <= t[0] ? Status.GREEN : statusValue > t[1] ? Status.RED : Status.YELLOW;
        }
        Status shown = raw;
        String n = note;
        if (raw == Status.RED) {
            Status before = entryRepo.findFirstByMetricKeyAndSnapshotDateLessThanOrderBySnapshotDateDesc(d.key(), today)
                    .map(e -> Status.ofCode(e.getRawStatus())).orElse(null);
            if (before != Status.RED) {
                shown = Status.YELLOW;
                n = note + " · первое нарушение, красный будет при повторе в следующем отчёте";
            }
        }
        return new Metric(d, value, prev, raw, shown, n);
    }

    private Double historyValue(String key, LocalDate date, LocalDate today) {
        return entryRepo.findFirstByMetricKeyAndSnapshotDateLessThanEqualOrderBySnapshotDateDesc(key, date)
                .filter(e -> e.getSnapshotDate().isBefore(today))
                .map(PulseSnapshotEntry::getMetricValue).orElse(null);
    }

    private static String signed(double v) {
        return (v > 0 ? "+" : v < 0 ? "−" : "") + AnalyticsService.fmtDouble(Math.round(Math.abs(v) * 10) / 10.0);
    }

    // ───────────────────────── вывод ─────────────────────────

    private static String valueText(Double v, String unit) {
        if (v == null) return "—";
        String n = AnalyticsService.fmtDouble(Math.round(v * 10) / 10.0);
        return unit == null || unit.isEmpty() ? n : n + " " + unit;
    }

    /** Красивый текст вкладки (HTML Telegram и HTML-экспорт). */
    public String formatHtml(Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("🫀 <b>Пульс проекта</b>\n");
        sb.append("<i>Считается на момент отчёта (").append(r.at().format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))).append(" UTC); окна «7 дн.» скользящие, сравнение с тем же окном неделей раньше. Период вкладки не выбирается.</i>\n\n");
        sb.append("Итог: ").append(r.project().icon).append(" <b>").append(r.project().title).append("</b> · сигналов 🟢 ").append(r.greens())
          .append(" / 🟡 ").append(r.yellows()).append(" / 🔴 ").append(r.reds()).append("\n");
        for (SignalResult s : r.signals()) {
            sb.append("\n").append(s.status().icon).append(" <b>").append(s.no()).append(". ").append(s.title()).append("</b>\n");
            for (Metric m : s.metrics()) {
                sb.append(m.shown().icon).append(" ").append(m.def().label()).append(": <b>").append(valueText(m.value(), m.def().unit())).append("</b>");
                sb.append(" (неделей раньше: ").append(valueText(m.prev(), m.def().unit())).append(")\n");
                sb.append("   <i>").append(m.note());
                if (m.def().red() != null) {
                    double[] t = thresholdsOf(m.def());
                    sb.append(" · порог: ").append(m.def().higherIsBetter() ? "зелёный от " : "зелёный до ").append(fmt(t[0]))
                      .append(m.def().higherIsBetter() ? ", красный ниже " : ", красный выше ").append(fmt(t[1]));
                }
                sb.append("</i>\n");
            }
        }
        sb.append("\n<i>Красный ставится только если порог нарушен в двух отчётах подряд; при первом нарушении метрика жёлтая. Серый - данных мало, причина указана под метрикой. Пороги меняются командой /pulsethr.</i>");
        return sb.toString();
    }

    // ───────────────────────── история и уведомления ─────────────────────────

    /** Ежедневный снимок в 03:00: пишет значения и статусы всех метрик, сигналов и проекта, при переходе сигнала в красный (и выходе из красного) шлёт админам одно сообщение. */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void dailySnapshot() {
        try {
            Result r = compute();
            LocalDate today = LocalDate.now();
            saveEntries(r, today);
            notifyChanges(r, today);
        } catch (Exception e) {
            log.error("Pulse daily snapshot failed", e);
        }
    }

    private void saveEntries(Result r, LocalDate today) {
        // Повторный запуск за день перезаписывает сегодняшний снимок; для сравнений берутся только записи строго раньше сегодняшней даты
        entryRepo.deleteBySnapshotDate(today);
        entryRepo.flush();
        List<PulseSnapshotEntry> rows = new ArrayList<>();
        for (SignalResult s : r.signals()) {
            for (Metric m : s.metrics()) rows.add(entry(today, m.def().key(), s.no(), m.value(), m.raw(), m.shown()));
            rows.add(entry(today, "signal:" + s.no(), s.no(), null, s.status(), s.status()));
        }
        rows.add(entry(today, "project", 0, null, r.project(), r.project()));
        entryRepo.saveAll(rows);
    }

    private static PulseSnapshotEntry entry(LocalDate date, String key, int signal, Double value, Status raw, Status shown) {
        PulseSnapshotEntry e = new PulseSnapshotEntry();
        e.setSnapshotDate(date);
        e.setMetricKey(key);
        e.setSignalNo(signal);
        e.setMetricValue(value);
        e.setRawStatus(raw.code);
        e.setShownStatus(shown.code);
        return e;
    }

    private void notifyChanges(Result r, LocalDate today) {
        for (SignalResult s : r.signals()) {
            Optional<PulseSnapshotEntry> before = entryRepo.findFirstByMetricKeyAndSnapshotDateLessThanOrderBySnapshotDateDesc("signal:" + s.no(), today);
            if (before.isEmpty()) continue;
            Status was = Status.ofCode(before.get().getShownStatus());
            boolean nowRed = s.status() == Status.RED, wasRed = was == Status.RED;
            if (nowRed == wasRed) continue;
            StringBuilder sb = new StringBuilder();
            sb.append(nowRed ? "🔴 <b>Пульс: сигнал перешёл в красный</b>\n\n" : "🟢 <b>Пульс: сигнал вышел из красного</b>\n\n");
            sb.append("<b>").append(s.no()).append(". ").append(s.title()).append("</b> (был: ").append(was.title).append(", стал: ").append(s.status().title).append(")\n\n");
            for (Metric m : s.metrics()) {
                if (nowRed && m.shown() != Status.RED) continue;
                sb.append(m.shown().icon).append(" ").append(m.def().label()).append(": <b>").append(valueText(m.value(), m.def().unit())).append("</b>");
                if (m.def().red() != null) {
                    double[] t = thresholdsOf(m.def());
                    sb.append(" · красная граница ").append(fmt(t[1]));
                }
                sb.append(" · неделей раньше: ").append(valueText(m.prev(), m.def().unit())).append("\n");
            }
            eventPublisher.publishEvent(new AnalyticsAlertEvent(this, sb.toString()));
        }
    }

    /** История значений для графика: дата;сигнал;метрика;значение;статус. Десятичный разделитель - запятая, чтобы Excel открывал числа сразу. */
    public String historyCsv() {
        StringBuilder sb = new StringBuilder("﻿дата;сигнал;метрика;значение;статус\n");
        for (PulseSnapshotEntry e : entryRepo.findAllByOrderBySnapshotDateAscSignalNoAscIdAsc()) {
            String signal = e.getSignalNo() >= 1 && e.getSignalNo() < SIGNAL_TITLES.length ? SIGNAL_TITLES[e.getSignalNo()] : "Проект";
            String label;
            if (e.getMetricKey().startsWith("signal:")) label = "Итог сигнала";
            else if ("project".equals(e.getMetricKey())) label = "Статус проекта";
            else {
                Def d = defOf(e.getMetricKey());
                label = d == null ? e.getMetricKey() : d.label();
            }
            String value = e.getMetricValue() == null ? "" : String.format(RU, "%.2f", e.getMetricValue());
            sb.append(e.getSnapshotDate()).append(';').append(csv(signal)).append(';').append(csv(label)).append(';')
              .append(value).append(';').append(e.getShownStatus()).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String s) {
        return s.contains(";") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
