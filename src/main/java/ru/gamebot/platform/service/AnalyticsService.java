package ru.gamebot.platform.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.FinanceEntry;
import ru.gamebot.platform.domain.model.IncidentEntry;
import ru.gamebot.platform.domain.model.PlatformSnapshot;
import ru.gamebot.platform.domain.model.RewardRequest;
import ru.gamebot.platform.domain.model.Tournament;
import ru.gamebot.platform.domain.model.TournamentEntry;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.ExcTransactionRepository;
import ru.gamebot.platform.domain.repository.IncidentEntryRepository;
import ru.gamebot.platform.domain.repository.PlatformSnapshotRepository;
import ru.gamebot.platform.domain.repository.QuestRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.domain.repository.TournamentEntryRepository;
import ru.gamebot.platform.domain.repository.TournamentRepository;

/**
 * Раздел «Аналитика» админ-панели (ТЗ «EGC - Метрики для управления проектом»): 11 вкладок, период (сегодня / 7 / 30 дней /
 * произвольный), сравнение с предыдущим периодом, тренды по ежедневным снимкам, выгрузка в CSV. Сервис только считает и
 * форматирует; кнопки и ввод периода - в боте. Метрики-остатки (всего игроков, EXC на счетах) считаются вживую, их значение
 * для предыдущего периода берётся из ближайшего ежедневного снимка (PlatformSnapshot); метрики-потоки (новых, выполнено,
 * начислено) - запросами по окну, для предыдущего периода - по такому же окну перед ним.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    public enum Tab {
        USERS("👥", "Пользователи и приток"),
        ACTIVITY("📈", "Активность"),
        ENGAGEMENT("🔥", "Вовлечённость"),
        RETENTION("🔄", "Удержание"),
        QUESTS("🎯", "Квесты"),
        ECONOMY("💰", "Экономика и выплаты"),
        WITHDRAWALS("💸", "Выводы игроков"),
        REFERRAL("🤝", "Реферальная программа"),
        SQUADS("👨‍👩‍👧‍👦", "Отряды"),
        SOURCES("📡", "Источники трафика"),
        PNL("📒", "Финансовая сводка"),
        TOURNAMENTS("🏆", "Турниры"),
        TECH("🛠", "Техническое здоровье"),
        PACKS("📦", "Пачки квестов"),
        FRAUD("🕵️", "Фрод и злоупотребления"),
        COURSE("📈", "Курс дня и красная кнопка"),
        KPI("🏪", "Витрина проекта"),
        EGC_PASS("⭐", "EGC Pass: продажи");

        public final String icon;
        public final String title;

        Tab(String icon, String title) {
            this.icon = icon;
            this.title = title;
        }

        public String label() {
            return icon + " " + title;
        }
    }

    /** Окно [from, to); label - подпись для шапки. */
    public record Period(LocalDateTime from, LocalDateTime to, String label) {
        public Period previous() {
            Duration d = Duration.between(from, to);
            return new Period(from.minus(d), from, "предыдущий период");
        }

        public static Period today() {
            LocalDateTime now = LocalDateTime.now();
            return new Period(now.toLocalDate().atStartOfDay(), now, "сегодня");
        }

        public static Period lastDays(int days) {
            LocalDateTime now = LocalDateTime.now();
            return new Period(now.minusDays(days), now, "последние " + days + " дн.");
        }

        /** Календарные даты включительно: с начала первого дня до конца последнего. */
        public static Period custom(LocalDate fromDate, LocalDate toDate) {
            DateTimeFormatter f = DateTimeFormatter.ofPattern("dd.MM.yyyy");
            return new Period(fromDate.atStartOfDay(), toDate.plusDays(1).atStartOfDay(),
                    fromDate.format(f) + " - " + toDate.format(f));
        }
    }

    /** Одна строка вкладки. prev = null - сравнивать не с чем («—»); lowerIsBetter - рост плохой (красная стрелка не нужна, но подсказка есть). */
    public record Line(String key, String label, String unit, Double value, Double prev, String note, boolean lowerIsBetter) {}

    public record TabData(Tab tab, Period period, List<Line> lines, List<String> extras) {}

    private static final Locale RU = Locale.forLanguageTag("ru");

    private final AppUserRepository userRepo;
    private final QuestSubmissionRepository submissionRepo;
    private final QuestRepository questRepo;
    private final PlatformSnapshotRepository snapRepo;
    private final TournamentRepository tournamentRepo;
    private final TournamentEntryRepository entryRepo;
    private final ExcTransactionRepository excRepo;
    private final ExcTransactionService excService;
    private final RewardService rewardService;
    private final UserService userService;
    private final QuestPackAnalyticsService packAnalyticsService;
    private final HealthRatioService healthRatioService;
    private final TrafficFunnelService funnelService;
    private final FinanceService financeService;
    private final ExpenseService expenseService;
    private final IncidentEntryRepository incidentRepo;
    private final HeartbeatService heartbeatService;
    private final ErrorMonitorService errorMonitor;
    private final RetentionService retentionService;
    private final CourseAnalyticsService courseAnalytics;
    private final EgcPassFunnelService egcPassFunnel;

    // ───────────────────────── снимок: дополнительные поля для трендов ─────────────────────────

    /** Дополняет ежедневный снимок метриками для трендов (PlatformSnapshotService.takeSnapshot); ошибки не пробрасываются. */
    public void applyExtras(PlatformSnapshot snap) {
        LocalDateTime now = LocalDateTime.now();
        try {
            UserService.EngagementReport er = userService.getEngagementReport(null);
            snap.setDau(er.dau());
            snap.setMau(er.mau());
            snap.setQuestTakers7d(er.weeklyQuestTakers());
            snap.setSecondQuestReturnPct(Math.round(er.retentionPercent()));
        } catch (Exception e) {
            log.warn("Snapshot extras: engagement failed", e);
        }
        try {
            snap.setPendingSubmissions(submissionRepo.countByStatus(SubmissionStatus.PENDING));
            Double avg = avgReviewMinutes(now.minusDays(7), now);
            snap.setAvgReviewMin7d(avg == null ? null : Math.round(avg));
            snap.setEarnedExc7d(excService.sumEarnedSince(now.minusDays(7)));
            snap.setPaidOutExc7d(rewardService.totalPaidOutExcSince(now.minusDays(7)));
            UserService.ReferralEconomicsSnapshot rs = userService.referralEconomicsSnapshot();
            snap.setReferralsTotal(rs.totalReferred());
            snap.setReferralsActivated(rs.referredWithAtLeastOneQuest());
            snap.setBlockedUsers(userRepo.countByBlockedTrue());
            Double up = heartbeatService.uptimePercent(now.minusDays(7), now);
            snap.setUptimePermille7d(up == null ? null : Math.round(up * 10));
        } catch (Exception e) {
            log.warn("Snapshot extras: counters failed", e);
        }
        try {
            UserService.SquadCohortReport inSquad = userService.getSquadCohortReport(true);
            UserService.SquadCohortReport noSquad = userService.getSquadCohortReport(false);
            UserService.SquadOverview overview = userService.squadOverview();
            snap.setPlayersInSquads(inSquad.segmentSize());
            snap.setSquadsCount(overview.squadCount());
            snap.setDauMauInSquadPct(Math.round(inSquad.engagement().dauMauPercent()));
            snap.setDauMauNoSquadPct(Math.round(noSquad.engagement().dauMauPercent()));
            snap.setSecondQuestReturnInSquadPct(Math.round(inSquad.engagement().retentionPercent()));
            snap.setSecondQuestReturnNoSquadPct(Math.round(noSquad.engagement().retentionPercent()));
        } catch (Exception e) {
            log.warn("Snapshot extras: squads failed", e);
        }
        try {
            QuestPackAnalyticsService.Report packs = packAnalyticsService.build();
            if (packs.hasRotation()) {
                snap.setDauMauRotatingPct(Math.round(packs.rotating().dauMauPercent()));
                snap.setDauMauControlPct(Math.round(packs.control().dauMauPercent()));
            }
        } catch (Exception e) {
            log.warn("Snapshot extras: quest packs failed", e);
        }
    }

    // ───────────────────────── общие помощники ─────────────────────────

    private Optional<PlatformSnapshot> snapAt(LocalDate date) {
        return snapRepo.findFirstBySnapshotDateLessThanEqualOrderBySnapshotDateDesc(date);
    }

    private Double snapVal(LocalDate date, Function<PlatformSnapshot, Long> f) {
        return snapAt(date).map(f).map(Long::doubleValue).orElse(null);
    }

    private static Line L(String key, String label, String unit, double value, Double prev) {
        return new Line(key, label, unit, value, prev, null, false);
    }

    private static Line L(String key, String label, String unit, Double value, Double prev, String note, boolean lowerIsBetter) {
        return new Line(key, label, unit, value, prev, note, lowerIsBetter);
    }

    private Double avgReviewMinutes(LocalDateTime from, LocalDateTime to) {
        List<Object[]> rows = submissionRepo.findReviewTimesBetween(from, to);
        if (rows.isEmpty()) return null;
        double sum = 0;
        for (Object[] r : rows) {
            sum += Math.max(0, Duration.between((LocalDateTime) r[0], (LocalDateTime) r[1]).toMinutes());
        }
        return sum / rows.size();
    }

    private static double pct(double part, double whole) {
        return whole <= 0 ? 0 : part * 100.0 / whole;
    }

    // ───────────────────────── вычисление вкладок ─────────────────────────

    public TabData compute(Tab tab, Period p) {
        Period pv = p.previous();
        LocalDate fromDate = p.from().toLocalDate();
        List<Line> lines = new ArrayList<>();
        List<String> extras = new ArrayList<>();
        try {
            switch (tab) {
                case USERS -> usersTab(p, pv, fromDate, lines);
                case ACTIVITY -> activityTab(fromDate, lines);
                case ENGAGEMENT -> engagementTab(fromDate, lines);
                case RETENTION -> retentionTab(lines, extras, null);
                case QUESTS -> questsTab(p, pv, fromDate, lines);
                case ECONOMY -> economyTab(p, pv, fromDate, lines);
                case REFERRAL -> referralTab(p, pv, fromDate, lines);
                case SQUADS -> squadsTab(fromDate, lines, extras);
                case WITHDRAWALS -> withdrawalsTab(p, pv, lines, extras);
                case PACKS -> packsTab(fromDate, lines, extras);
                case SOURCES -> sourcesTab(p, pv, lines, extras);
                case PNL -> pnlTab(p, pv, lines, extras);
                case TOURNAMENTS -> tournamentsTab(lines, extras);
                case TECH -> techTab(p, pv, lines, extras);
                case FRAUD -> fraudTab(p, lines, extras);
                case COURSE -> courseTab(p, pv, lines, extras);
                case KPI -> kpiTab(p, pv, fromDate, lines, extras);
                case EGC_PASS -> egcPassTab(p, pv, lines, extras);
            }
        } catch (Exception e) {
            log.error("Analytics tab {} failed", tab, e);
            extras.add("⚠️ Часть данных не удалось посчитать, подробности в логе (раздел «Проверка ошибок»).");
        }
        return new TabData(tab, p, lines, extras);
    }

    /** Вкладка «Удержание»: когорты D1/D7/D30 по журналу активных дней (определения - в RetentionService). Период вкладки не влияет. */
    /** Вкладка «Удержание» с одним выбранным разрезом (переключатель в боте): segKey = src | game | qtype | squad; null - все разрезы (полный отчёт). */
    public TabData computeRetention(String segKey) {
        List<Line> lines = new ArrayList<>();
        List<String> extras = new ArrayList<>();
        try {
            retentionTab(lines, extras, segKey);
        } catch (Exception e) {
            log.error("Analytics tab RETENTION failed", e);
            extras.add("⚠️ Часть данных не удалось посчитать, подробности в логе (раздел «Проверка ошибок»).");
        }
        return new TabData(Tab.RETENTION, Period.lastDays(7), lines, extras);
    }

    private void retentionTab(List<Line> out, List<String> extras, String segKey) {
        RetentionService.Report r = retentionService.report();
        out.add(L("ret_d1", "Возврат D1 (4 зрелые недели)", "%", pctOrNull(r.recentD1()), pctOrNull(r.prevD1()), "против предыдущих 4 зрелых недель", false));
        out.add(L("ret_d7", "Возврат D7 (4 зрелые недели)", "%", pctOrNull(r.recentD7()), pctOrNull(r.prevD7()), "против предыдущих 4 зрелых недель", false));
        out.add(L("ret_d30", "Возврат D30 (4 зрелые недели)", "%", pctOrNull(r.recentD30()), pctOrNull(r.prevD30()), "против предыдущих 4 зрелых недель", false));
        out.add(L("ret_users", "Игроков в когортах журнала", "", (double) r.cohortUsers(), null));

        StringBuilder head = new StringBuilder("<i>Возврат на день N = игрок был активен ровно в день «регистрация + N» (сутки по Москве). "
                + "Окно 7/30 = был активен хотя бы раз за эти дни. Когорта = неделя регистрации (с понедельника). "
                + "«—» = день ещё не наступил или не покрыт журналом, «мало» = в выборке меньше " + RetentionService.MIN_SAMPLE + " человек. "
                + "Показатели вкладки «Отряды» считаются по другому определению («активен за последние N дней»).</i>\n");
        if (r.journalStart() == null) {
            head.append("\n⚠️ Журнал активности пока пуст: цифры появятся после первых заходов игроков.");
        } else {
            java.time.LocalDate firstMonday = r.journalStart().with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
            DateTimeFormatter df = DateTimeFormatter.ofPattern("dd.MM");
            head.append("\nЖурнал ведётся с <b>").append(r.journalStart().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
                    .append("</b>: игроки, пришедшие раньше, в расчёт не входят (историю восстановить нельзя). Сейчас в когортах журнала: <b>")
                    .append(r.cohortUsers()).append("</b>.\nПервые цифры в таблице недель (первая полная неделя с ").append(firstMonday.format(df))
                    .append("): D1 с ").append(firstMonday.plusDays(8).format(df)).append(", D7 с ").append(firstMonday.plusDays(14).format(df))
                    .append(", D30 с ").append(firstMonday.plusDays(37).format(df))
                    .append(". В разрезах цифры идут раньше: у каждого игрока по его собственному дню (D1 с ").append(r.journalStart().plusDays(2).format(df)).append(").");
        }
        extras.add(head.toString());

        StringBuilder weeks = new StringBuilder("<b>Когорты по неделям регистрации</b>\n<pre>неделя  игр   D1   D7  D30  ок7 ок30\n");
        DateTimeFormatter wf = DateTimeFormatter.ofPattern("dd.MM");
        for (RetentionService.WeekRow w : r.weeks()) {
            weeks.append(String.format("%-7s %4d %4s %4s %4s %4s %4s%n", w.weekStart().format(wf), w.size(),
                    w.d1().text(), w.d7().text(), w.d30().text(), w.w7().text(), w.w30().text()));
        }
        if (r.weeks().isEmpty()) weeks.append("(нет данных)\n");
        weeks.append("</pre>");
        extras.add(weeks.toString());

        for (java.util.Map.Entry<String, List<RetentionService.SegmentRow>> seg : r.segments().entrySet()) {
            if (segKey != null && !segKey.equals(seg.getKey())) continue;
            StringBuilder sb = new StringBuilder("<b>Разрез: ").append(escape(RetentionService.SEGMENT_TITLES.getOrDefault(seg.getKey(), seg.getKey()))).append("</b>\n<pre>значение          игр   D1   D7  D30\n");
            int shown = 0;
            for (RetentionService.SegmentRow row : seg.getValue()) {
                if (shown++ >= 6) break;
                String label = row.label() == null ? "—" : row.label();
                if (label.length() > 16) label = label.substring(0, 15) + "…";
                sb.append(String.format("%-16s %4d %4s %4s %4s%n", escape(label), row.size(), row.d1().text(), row.d7().text(), row.d30().text()));
            }
            if (seg.getValue().isEmpty()) sb.append("(нет данных)\n");
            sb.append("</pre>");
            extras.add(sb.toString());
        }
        extras.add("<i>В разрезах у каждого показателя своя выборка: берутся игроки, у которых день N уже прошёл. Тип источника задаётся командой "
                + "кнопкой «🏷 Тип источника» в карточке источника (или командой <code>/srctype КОД paid|partner|organic|referral|auto</code>); без неё метка с расходом считается платным закупом.</i>");

        if (!r.costs().isEmpty()) {
            StringBuilder sb = new StringBuilder("<b>Стоимость вернувшегося игрока</b> <i>(расход источника, введён вручную; с периодом - по игрокам этого периода)</i>\n");
            int shown = 0;
            for (RetentionService.SourceCost c : r.costs()) {
                if (shown++ >= 5) break;
                sb.append("• <code>").append(escape(c.code())).append("</code> ").append(c.period() == null ? "" : c.period() + " ").append(c.spendRub()).append(" ₽: ");
                sb.append(c.registered() > 0 ? Math.round((double) c.spendRub() / c.registered()) + " ₽/регистрация" : "—");
                sb.append("; D7: ").append(costText(c.spendRub(), c.n7(), c.returned7()));
                sb.append("; D30: ").append(costText(c.spendRub(), c.n30(), c.returned30())).append("\n");
            }
            extras.add(sb.toString());
        }
    }

    private static Double pctOrNull(RetentionService.Rate r) {
        Integer p = r == null ? null : r.percent();
        return p == null ? null : p.doubleValue();
    }

    /** «₽ за вернувшегося» = расход / вернувшиеся; если выборка меньше минимума - с пометкой «ориентир». */
    private static String costText(long spend, long n, long returned) {
        if (n == 0) return "—";
        if (returned == 0) return "пока никто не вернулся (n=" + n + ")";
        String v = Math.round((double) spend / returned) + " ₽";
        return n < RetentionService.MIN_SAMPLE ? v + " (ориентир, n=" + n + ")" : v + " (n=" + n + ")";
    }

    private void usersTab(Period p, Period pv, LocalDate fromDate, List<Line> out) {
        long total = userService.totalRegisteredUsers();
        out.add(L("total", "Всего игроков", "", (double) total, snapVal(fromDate, PlatformSnapshot::getTotalUsers)));
        long fresh = userRepo.countRegisteredBetween(p.from(), p.to());
        long freshPrev = userRepo.countRegisteredBetween(pv.from(), pv.to());
        out.add(L("new", "Новых за период", "", (double) fresh, (double) freshPrev));
        Double yesterday = snapVal(LocalDate.now().minusDays(1), PlatformSnapshot::getTotalUsers);
        out.add(L("dod", "Прирост базы к вчерашнему снимку", "", yesterday == null ? null : total - yesterday, null,
                "скорость роста базы: сколько игроков прибавилось со вчерашнего дня", false));
        out.add(L("growth", "Рост базы за период", "%", pct(fresh, Math.max(1, total - fresh)), pct(freshPrev, Math.max(1, total - fresh - freshPrev)),
                "новых относительно базы на начало периода", false));
        long viaAds = userRepo.countByTrafficSourceCodeIsNotNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(p.from(), p.to());
        long viaRef = userRepo.countByReferredByTelegramIdIsNotNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(p.from(), p.to());
        long organic = Math.max(0, fresh - viaAds - viaRef);
        out.add(L("new_ads", "  из них по рекламным ссылкам", "", (double) viaAds, null));
        out.add(L("new_ref", "  из них по рефералке", "", (double) viaRef, null));
        out.add(L("new_org", "  органика (остальные)", "", (double) organic, null));
    }

    private void activityTab(LocalDate fromDate, List<Line> out) {
        LocalDate today = LocalDate.now();
        LocalDateTime nowDt = LocalDateTime.now();
        long total = Math.max(1, userService.totalRegisteredUsers());
        long a7 = userRepo.countActiveSince(today.minusDays(7));
        long a30 = userRepo.countActiveSince(today.minusDays(30));
        Double a7p = snapVal(fromDate, PlatformSnapshot::getActive7Days);
        Double a30p = snapVal(fromDate, PlatformSnapshot::getActive30Days);
        out.add(L("active7", "Активны за 7 дней", "", (double) a7, a7p));
        out.add(L("active7pct", "  доля от базы", "%", pct(a7, total), null));
        out.add(L("active30", "Активны за 30 дней", "", (double) a30, a30p));
        out.add(L("active30pct", "  доля от базы", "%", pct(a30, total), null));
        UserService.RetentionReport rr = userService.retention();
        out.add(L("ret7", "Возврат за 7 дней", "%", rr.percent7(), snapVal(fromDate, PlatformSnapshot::getRetention7Pct),
                "из пришедших 7-14 дней назад активны за последние 7 дней; когорта " + rr.cohort7() + " чел.", false));
        out.add(L("ret30", "Возврат за 30 дней", "%", rr.percent30(), snapVal(fromDate, PlatformSnapshot::getRetention30Pct),
                "из пришедших 30-60 дней назад активны за последние 30 дней; когорта " + rr.cohort30() + " чел. "
                        + "Считает и общая статистика, и витрина рекламодателя. «Возврат за вторым квестом» - другая, более строгая метрика", false));
    }

    private void engagementTab(LocalDate fromDate, List<Line> out) {
        UserService.EngagementReport er = userService.getEngagementReport(null);
        Double dauPrev = snapVal(fromDate, PlatformSnapshot::getDau);
        Double mauPrev = snapVal(fromDate, PlatformSnapshot::getMau);
        out.add(L("dau", "DAU (активны за 24 ч)", "", (double) er.dau(), dauPrev));
        out.add(L("mau", "MAU (активны за 30 дней)", "", (double) er.mau(), mauPrev));
        Double ratioPrev = (dauPrev != null && mauPrev != null && mauPrev > 0) ? dauPrev * 100.0 / mauPrev : null;
        out.add(L("dau_mau", "DAU / MAU", "%", er.dauMauPercent(), ratioPrev,
                "норма: 10-20% окей, 20%+ хорошо, 25-30%+ уровень топ-игр", false));
        out.add(L("takers7", "Взяли ≥1 квест за 7 дней", "", (double) er.weeklyQuestTakers(), snapVal(fromDate, PlatformSnapshot::getQuestTakers7d)));
        out.add(L("takers7pct", "  доля от MAU", "%", er.weeklyQuestPercent(), null, "норма: 5-15% окей, 15%+ сильно", false));
        out.add(L("second", "Возврат за вторым квестом", "%", er.retentionPercent(), snapVal(fromDate, PlatformSnapshot::getSecondQuestReturnPct),
                "из когорты «1-й квест ≥7 дн. назад»; в когорте " + er.retentionCohort() + " чел.", false));
    }

    /** ТЗ EGC_TZ_otryady (2026-10-01), Этап 2: заведена после того, как разовая диагностика Этапа 1
     *  (UserService.getSquadCohortReport, /squadretention) подтвердила разрыв удержания "в отряде" vs
     *  "без отряда" (DAU/MAU ×18, возврат за 2-й квест ×2,5) и перекрёстная проверка с рефералкой
     *  исключила, что это тень качества реферального трафика (squadReferralCrossCheck). */
    private void squadsTab(LocalDate fromDate, List<Line> out, List<String> extras) {
        UserService.SquadCohortReport inSquad = userService.getSquadCohortReport(true);
        UserService.SquadCohortReport noSquad = userService.getSquadCohortReport(false);
        UserService.SquadOverview overview = userService.squadOverview();
        long total = Math.max(1, userService.totalRegisteredUsers());

        out.add(L("in_squad", "Игроков в отрядах", "", (double) inSquad.segmentSize(), snapVal(fromDate, PlatformSnapshot::getPlayersInSquads)));
        out.add(L("in_squad_pct", "  доля от базы", "%", pct(inSquad.segmentSize(), total), null));
        out.add(L("squad_count", "Отрядов (активных)", "", (double) overview.squadCount(), snapVal(fromDate, PlatformSnapshot::getSquadsCount)));
        out.add(L("squad_avg_size", "Средний размер отряда", "", overview.avgSize(), null,
                "мал - отряды пока ближе к \"тег на паре игроков\", чем к команде", false));

        Double dauMauInPrev = snapVal(fromDate, PlatformSnapshot::getDauMauInSquadPct);
        Double dauMauNoPrev = snapVal(fromDate, PlatformSnapshot::getDauMauNoSquadPct);
        out.add(L("dau_mau_in", "DAU/MAU — в отряде", "%", inSquad.engagement().dauMauPercent(), dauMauInPrev));
        out.add(L("dau_mau_no", "DAU/MAU — без отряда", "%", noSquad.engagement().dauMauPercent(), dauMauNoPrev));

        UserService.SquadMatchedReport matchedIn = userService.getSquadMatchedReport(true, 3);
        UserService.SquadMatchedReport matchedNo = userService.getSquadMatchedReport(false, 3);
        out.add(L("dau_mau_in_cmp", "DAU/MAU — в отряде (3+ квестов, аккаунт 14+ дн.)", "%", matchedIn.dauMauPercent(), null,
                "сопоставимые игроки: " + matchedIn.players() + " чел.", false));
        out.add(L("dau_mau_no_cmp", "DAU/MAU — без отряда (3+ квестов, аккаунт 14+ дн.)", "%", matchedNo.dauMauPercent(), null,
                "сопоставимые игроки: " + matchedNo.players() + " чел.", false));
        out.add(L("active30_in_cmp", "Заходили за 30 дн. — в отряде (3+ квестов, 14+ дн.)", "%", matchedIn.active30Percent(), null,
                "доля игроков группы; не зависит от размера MAU", false));
        out.add(L("active30_no_cmp", "Заходили за 30 дн. — без отряда (3+ квестов, 14+ дн.)", "%", matchedNo.active30Percent(), null,
                "доля игроков группы; не зависит от размера MAU", false));

        out.add(L("ret7_in", "Возврат 7д — в отряде", "%", inSquad.retention().percent7(), null,
                "когорта " + inSquad.retention().cohort7() + " чел.", false));
        out.add(L("ret7_no", "Возврат 7д — без отряда", "%", noSquad.retention().percent7(), null,
                "когорта " + noSquad.retention().cohort7() + " чел.", false));
        out.add(L("ret30_in", "Возврат 30д — в отряде", "%", inSquad.retention().percent30(), null,
                "когорта " + inSquad.retention().cohort30() + " чел.", false));
        out.add(L("ret30_no", "Возврат 30д — без отряда", "%", noSquad.retention().percent30(), null,
                "когорта " + noSquad.retention().cohort30() + " чел.", false));

        Double secondInPrev = snapVal(fromDate, PlatformSnapshot::getSecondQuestReturnInSquadPct);
        Double secondNoPrev = snapVal(fromDate, PlatformSnapshot::getSecondQuestReturnNoSquadPct);
        out.add(L("second_in", "Возврат за 2-й квест — в отряде", "%", inSquad.engagement().retentionPercent(), secondInPrev,
                "когорта " + inSquad.engagement().retentionCohort() + " чел. — самая строгая метрика", false));
        out.add(L("second_no", "Возврат за 2-й квест — без отряда", "%", noSquad.engagement().retentionPercent(), secondNoPrev,
                "когорта " + noSquad.engagement().retentionCohort() + " чел.", false));

        extras.add("ℹ️ <b>Как читать «Отряды»</b>\n\nОбщий DAU/MAU «в отряде» против «без отряда» завышает разрыв: вне отрядов много однодневных новичков с закупа, а в отряды вступают уже активные. Сравнивайте строки «(3+ квестов, аккаунт 14+ дн.)» - там игроки сопоставимы. Это связь, а не доказанная причина: с 17.10 дата вступления сохраняется (squadJoinedAt), через 3-4 недели можно сравнить активность одного игрока до и после вступления.");

        List<UserService.SquadLeaderboardEntry> top = userService.topActiveSquadsByQuests7d(5);
        if (top.isEmpty()) {
            extras.add("🏆 <b>Топ-5 отрядов за 7 дней</b>\n\nНикто из отрядов не выполнил квест за последние 7 дней.");
        } else {
            StringBuilder sb = new StringBuilder("🏆 <b>Топ-5 отрядов за 7 дней</b> (по одобренным квестам участников)\n\n");
            String[] medals = {"🥇", "🥈", "🥉"};
            for (int i = 0; i < top.size(); i++) {
                UserService.SquadLeaderboardEntry e = top.get(i);
                sb.append(i < 3 ? medals[i] : (i + 1) + ".").append(" ").append(e.squad().getName())
                  .append(" — ").append(e.approvedQuests7d()).append(" квест(ов)\n");
            }
            extras.add(sb.toString());
        }
    }

    /** Вкладка «Пачки квестов» (2026-10-03): честная оценка эффекта ротации — когорта «основная игра ротируется» против
     *  контрольной (остальные игры), см. QuestPackAnalyticsService. Смотреть РАЗНИЦУ между группами до и после старта. */
    private void packsTab(LocalDate fromDate, List<Line> out, List<String> extras) {
        QuestPackAnalyticsService.Report r = packAnalyticsService.build();
        DateTimeFormatter df = DateTimeFormatter.ofPattern("dd.MM HH:mm");
        if (!r.hasRotation()) {
            extras.add("📦 <b>Ротация пачек пока не включена ни для одной игры.</b>\n\nВключить: Игровые квесты → игра → 📦 Пачки квестов → 🗓 Расписание ротации. После включения здесь появится сравнение игроков ротируемых игр с контрольной группой.");
        } else {
            extras.add("📦 <b>Как читать</b>\n\nИгроки делятся по основной игре (где больше всего выполненных квестов за 60 дней). Ротируются: <b>"
                    + String.join(", ", r.rotatingGames()) + "</b>"
                    + (r.rotationSince() == null ? "" : ", с " + r.rotationSince().format(df))
                    + ". Контроль — все остальные. Важна разница между группами до и после старта: общий рост DAU/MAU может быть связан с отрядами и другими изменениями. Первые 1–2 цикла завышены эффектом новизны — оценивать по 3–4 циклу.");
        }
        out.add(L("rot_size", "Игроков: основная игра ротируется", "", (double) r.rotating().size(), null));
        out.add(L("ctl_size", "Игроков: контрольная группа", "", (double) r.control().size(), null));
        out.add(L("rot_dau_mau", "DAU/MAU — ротируемые игры", "%", r.rotating().dauMauPercent(), snapVal(fromDate, PlatformSnapshot::getDauMauRotatingPct)));
        out.add(L("ctl_dau_mau", "DAU/MAU — контроль", "%", r.control().dauMauPercent(), snapVal(fromDate, PlatformSnapshot::getDauMauControlPct)));
        out.add(L("rot_a7", "Активны за 7 дн. — ротируемые", "%", r.rotating().active7Percent(), null));
        out.add(L("ctl_a7", "Активны за 7 дн. — контроль", "%", r.control().active7Percent(), null));

        StringBuilder games = new StringBuilder("🎮 <b>Выполнено квестов по играм</b> (14 дн. → прошлые 14 дн.)\n\n");
        int shown = 0;
        for (QuestPackAnalyticsService.GameDelta g : r.games()) {
            if (shown++ >= 10) break;
            games.append(g.rotating() ? "🔄 " : "▫️ ").append(g.gameName()).append(" — ").append(g.last14())
                    .append(" (было ").append(g.prev14()).append(")\n");
        }
        games.append("\n🔄 — игра с включённой ротацией.");
        extras.add(games.toString());

        StringBuilder sw = new StringBuilder("🗓 <b>Журнал смен пачек</b>\n\n");
        if (r.recentSwitches().isEmpty()) {
            sw.append("Смен ещё не было.");
        } else {
            for (var s : r.recentSwitches()) {
                sw.append(s.getSwitchedAt() == null ? "—" : s.getSwitchedAt().format(df)).append(" — ").append(s.getGameName())
                        .append(" → «").append(s.getPackName()).append("» (")
                        .append(ru.gamebot.platform.domain.model.QuestPackSwitchLog.AUTO.equals(s.getSource()) ? "авто" : "вручную").append(")\n");
            }
        }
        extras.add(sw.toString());
    }

    private void questsTab(Period p, Period pv, LocalDate fromDate, List<Line> out) {
        long done = submissionRepo.countApprovedBetween(p.from(), p.to());
        long donePrev = submissionRepo.countApprovedBetween(pv.from(), pv.to());
        out.add(L("done", "Выполнено за период", "", (double) done, (double) donePrev));
        out.add(L("done_total", "Выполнено всего", "", (double) submissionRepo.countAllApproved(), snapVal(fromDate, PlatformSnapshot::getTotalApprovedQuests)));
        out.add(L("done_month", "Выполнено за 30 дней", "", (double) submissionRepo.countApprovedSince(LocalDateTime.now().minusDays(30)), snapVal(fromDate, PlatformSnapshot::getApprovedQuestsMonth)));
        out.add(L("pool", "Активных квестов в пуле", "", (double) questRepo.countByActiveTrue(), snapVal(fromDate, PlatformSnapshot::getActiveQuestsCount),
                "хватает ли контента на текущую базу (подробности - «🧭 Пул квестов»)", false));
        long pending = submissionRepo.countByStatus(SubmissionStatus.PENDING);
        out.add(L("pending", "На модерации сейчас", "", (double) pending, snapVal(fromDate, PlatformSnapshot::getPendingSubmissions), null, true));
        List<LocalDateTime> waiting = submissionRepo.findPendingCreatedAt();
        LocalDateTime now = LocalDateTime.now();
        double avgWait = waiting.isEmpty() ? 0 : waiting.stream().mapToLong(t -> Duration.between(t, now).toMinutes()).average().orElse(0);
        out.add(L("wait", "Среднее ожидание в очереди", "мин", avgWait, null, "сколько уже ждут заявки на модерации", true));
        long rejected = submissionRepo.countRejectedBetween(p.from(), p.to());
        long rejectedPrev = submissionRepo.countRejectedBetween(pv.from(), pv.to());
        out.add(L("approve_rate", "Доля одобренных заявок", "%", pct(done, done + rejected), pct(donePrev, donePrev + rejectedPrev),
                "одобрено / (одобрено + отклонено) за период", false));
    }

    private void economyTab(Period p, Period pv, LocalDate fromDate, List<Line> out) {
        long earned = excService.sumEarnedSince(p.from()) - excService.sumEarnedSince(p.to());
        long earnedPrev = excService.sumEarnedSince(pv.from()) - excService.sumEarnedSince(pv.to());
        out.add(L("earned", "Начислено EXC за период", "EXC", (double) earned, (double) earnedPrev, "темп генерации внутренней валюты", false));
        long coins = userService.sumAllCoins();
        out.add(L("coins", "EXC на счетах (всего)", "EXC", (double) coins, snapVal(fromDate, PlatformSnapshot::getTotalCoinsOnAccounts),
                "объём «зависшей» валюты в системе", false));
        RewardService.WithdrawalPeriodStats cur = withdrawalsBetween(p.from(), p.to());
        RewardService.WithdrawalPeriodStats prev = withdrawalsBetween(pv.from(), pv.to());
        out.add(L("paid_exc", "Выплачено за период", "EXC", (double) cur.totalExc(), (double) prev.totalExc()));
        out.add(L("paid_rub", "  рублями", "₽", (double) cur.totalRub(), (double) prev.totalRub()));
        out.add(L("paid_ton", "  GRAM/TON (эквивалент)", "₽", (double) cur.totalTonRub(), (double) prev.totalTonRub()));
        out.add(L("paid_stars", "  звёздами Telegram", "⭐", (double) cur.totalStars(), (double) prev.totalStars()));
        out.add(L("paid_total", "Выплачено всего", "EXC", (double) rewardService.totalPaidOutExc(), snapVal(fromDate, PlatformSnapshot::getTotalPaidOutExc)));
        long recipients = rewardService.countUniqueWithdrawalRecipients();
        long base = Math.max(1, userService.totalRegisteredUsers());
        out.add(L("recipients", "Получателей выплат", "", (double) recipients, snapVal(fromDate, PlatformSnapshot::getUniqueWithdrawalRecipients)));
        out.add(L("recipients_pct", "  доля базы, которая выводит", "%", pct(recipients, base), null));
        out.add(L("tickets", "Билетов в обороте", "", (double) userService.sumAllTickets(), snapVal(fromDate, PlatformSnapshot::getTotalTickets)));
        out.add(L("queue", "Заявок на награды в очереди", "", (double) rewardService.countPendingRequests(), null, null, true));
        out.add(L("queue_w", "Заявок на вывод в очереди", "", (double) rewardService.findPendingWithdrawals().size(), null, null, true));
    }

    /** Вкладка «Выводы игроков» (2026-10-04): сколько и как выводят — за период, в среднем за месяц, по месяцам и способам,
     *  очередь и «на сколько хватит пула». Момент выплаты - paid_at, а у старых заявок (до 26.09.2026) без него - дата создания;
     *  рубли в заявках записываются только с сентября, поэтому для старых месяцев показываем EXC и ₽-эквивалент по текущему курсу. */
    private void withdrawalsTab(Period p, Period pv, List<Line> out, List<String> extras) {
        List<RewardService.WithdrawalRow> rows = rewardService.approvedWithdrawalRows();
        double ratio = healthRatioService.getCurrentRatio();
        LocalDateTime now = LocalDateTime.now();

        long cnt = 0, cntPrev = 0, exc = 0, excPrev = 0, rub = 0, rubPrev = 0, stars = 0, starsPrev = 0;
        java.util.Set<Long> players = new java.util.HashSet<>(), playersPrev = new java.util.HashSet<>();
        for (RewardService.WithdrawalRow r : rows) {
            boolean inCur = !r.at().isBefore(p.from()) && r.at().isBefore(p.to());
            boolean inPrev = !r.at().isBefore(pv.from()) && r.at().isBefore(pv.to());
            if (inCur) {
                cnt++; exc += r.exc(); rub += r.rub(); players.add(r.userId());
                if ("STARS".equals(r.method())) stars += r.exc();
            } else if (inPrev) {
                cntPrev++; excPrev += r.exc(); rubPrev += r.rub(); playersPrev.add(r.userId());
                if ("STARS".equals(r.method())) starsPrev += r.exc();
            }
        }
        out.add(L("w_cnt", "Выплат за период", "", (double) cnt, (double) cntPrev));
        out.add(L("w_players", "Игроков получили выплату", "", (double) players.size(), (double) playersPrev.size()));
        out.add(L("w_exc", "Выведено за период", "EXC", (double) exc, (double) excPrev));
        out.add(L("w_rub", "  рублями и TON (по заявкам)", "₽", (double) rub, (double) rubPrev,
                "у выводов в Stars и у старых заявок рублей в записи нет", false));
        out.add(L("w_stars", "  в Stars", "EXC", (double) stars, (double) starsPrev));
        out.add(L("w_avg", "Средняя выплата", "EXC", cnt == 0 ? 0.0 : (double) exc / cnt, cntPrev == 0 ? null : (double) excPrev / cntPrev));

        // последние 30 дней против предыдущих 30 и среднее за всё время
        LocalDateTime t30 = now.minusDays(30), t60 = now.minusDays(60);
        long exc30 = 0, excP30 = 0, cnt30 = 0, total = 0;
        LocalDateTime first = null;
        for (RewardService.WithdrawalRow r : rows) {
            total += r.exc();
            if (first == null || r.at().isBefore(first)) first = r.at();
            if (!r.at().isBefore(t30)) { exc30 += r.exc(); cnt30++; }
            else if (!r.at().isBefore(t60)) { excP30 += r.exc(); }
        }
        out.add(L("w_30", "Последние 30 дней", "EXC", (double) exc30, (double) excP30,
                "в сравнении с предыдущими 30 днями - главный индикатор роста вывода", true));
        long days = first == null ? 1 : Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(first, now));
        out.add(L("w_avg_month", "Среднее за 30 дней (за всё время)", "EXC", (double) total * 30.0 / days, null,
                "с " + (first == null ? "—" : first.toLocalDate()) + ", " + days + " дн.", false));

        // очередь и запас пула
        List<RewardRequest> pending = rewardService.findPendingWithdrawals();
        long pendingExc = pending.stream().mapToLong(rewardService::actualPaidPrice).sum();
        out.add(L("w_queue", "Заявок на вывод в очереди", "", (double) pending.size(), null, null, true));
        out.add(L("w_queue_exc", "  на сумму", "EXC", (double) pendingExc, null, null, true));
        long poolRub = healthRatioService.getPayoutPoolRub();
        out.add(L("w_pool", "Пул выплат", "₽", (double) poolRub, null, "курс сейчас: 1000 EXC = " + String.format("%.1f", ratio * 10) + " ₽", false));
        double burn30Rub = exc30 * ratio / 100.0;
        if (burn30Rub > 0) {
            out.add(L("w_runway", "Хватит пула при темпе последних 30 дн.", "мес.", poolRub / burn30Rub, null,
                    "пул ÷ (выведено за 30 дн. × текущий курс); без учёта притока в пул", false));
        }

        // по месяцам (последние 12) и способам за 30 дней
        java.util.TreeMap<java.time.YearMonth, long[]> byMonth = new java.util.TreeMap<>();
        java.util.Map<java.time.YearMonth, java.util.Set<Long>> monthPlayers = new java.util.HashMap<>();
        for (RewardService.WithdrawalRow r : rows) {
            java.time.YearMonth ym = java.time.YearMonth.from(r.at());
            long[] a = byMonth.computeIfAbsent(ym, k -> new long[3]);
            a[0]++; a[1] += r.exc(); a[2] += r.rub();
            monthPlayers.computeIfAbsent(ym, k -> new java.util.HashSet<>()).add(r.userId());
        }
        StringBuilder sb = new StringBuilder("📅 <b>Выводы по месяцам</b> (выплаты / игроков / EXC / ₽ по заявкам)\n\n");
        java.util.List<java.time.YearMonth> months = new java.util.ArrayList<>(byMonth.keySet());
        for (int i = Math.max(0, months.size() - 12); i < months.size(); i++) {
            java.time.YearMonth ym = months.get(i);
            long[] a = byMonth.get(ym);
            sb.append(ym).append(": ").append(a[0]).append(" / ").append(monthPlayers.get(ym).size()).append(" / ")
                    .append(String.format("%,d", a[1]).replace(',', ' ')).append(" EXC");
            sb.append(a[2] > 0 ? " / " + String.format("%,d", a[2]).replace(',', ' ') + " ₽" : " / — ₽").append("\n");
        }
        sb.append("\nНеполный текущий месяц сравнивать с полными нельзя. До 26.09.2026 рубли в заявках не записывались.");
        extras.add(sb.toString());

        long[] rubM = new long[2], tonM = new long[2], starM = new long[2];
        for (RewardService.WithdrawalRow r : rows) {
            if (r.at().isBefore(t30)) continue;
            long[] a = "STARS".equals(r.method()) ? starM : "TON".equals(r.method()) ? tonM : rubM;
            a[0]++; a[1] += r.exc();
        }
        extras.add("💳 <b>По способам за последние 30 дней</b>\n\n"
                + "Рубли (СБП/карта): " + rubM[0] + " выплат, " + String.format("%,d", rubM[1]).replace(',', ' ') + " EXC\n"
                + "GRAM/TON: " + tonM[0] + " выплат, " + String.format("%,d", tonM[1]).replace(',', ' ') + " EXC\n"
                + "Stars: " + starM[0] + " выплат, " + String.format("%,d", starM[1]).replace(',', ' ') + " EXC");
    }

    /** Выплаты в окне [from, to): разность двух накопительных «с момента» - других запросов по окну в сервисе выплат нет. */
    /** Выплаты игрокам в рублях (₽ + GRAM в рублях) за календарные даты включительно - для панели «Финансы». */
    public long payoutsRub(LocalDate from, LocalDate to) {
        RewardService.WithdrawalPeriodStats w = withdrawalsBetween(from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        return w.totalRub() + w.totalTonRub();
    }

    private RewardService.WithdrawalPeriodStats withdrawalsBetween(LocalDateTime from, LocalDateTime to) {
        RewardService.WithdrawalPeriodStats a = rewardService.withdrawalStatsSince(from);
        RewardService.WithdrawalPeriodStats b = rewardService.withdrawalStatsSince(to);
        return new RewardService.WithdrawalPeriodStats(a.count() - b.count(), a.totalExc() - b.totalExc(),
                a.totalRub() - b.totalRub(), a.totalTonRub() - b.totalTonRub(), a.totalStars() - b.totalStars());
    }

    private void referralTab(Period p, Period pv, LocalDate fromDate, List<Line> out) {
        UserService.ReferralEconomicsSnapshot rs = userService.referralEconomicsSnapshot();
        out.add(L("ref_total", "Всего рефералов", "", (double) rs.totalReferred(), snapVal(fromDate, PlatformSnapshot::getReferralsTotal)));
        out.add(L("ref_new", "Новых рефералов за период", "",
                (double) userRepo.countByReferredByTelegramIdIsNotNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(p.from(), p.to()),
                (double) userRepo.countByReferredByTelegramIdIsNotNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(pv.from(), pv.to())));
        out.add(L("ref_act", "Активировано (≥1 квест)", "", (double) rs.referredWithAtLeastOneQuest(), snapVal(fromDate, PlatformSnapshot::getReferralsActivated)));
        out.add(L("ref_act_pct", "  доля от всех рефералов", "%", pct(rs.referredWithAtLeastOneQuest(), rs.totalReferred()), null, "качество приведённых игроков", false));
        out.add(L("ref_paid", "Выплачено по отчислениям (всего)", "EXC", (double) rs.totalReferralTrickleExc(), null));
        out.add(L("ref_earners", "Рефереров с реальным доходом", "", (double) rs.referrersWithTrickleEarnings(), null));
        long avgIncome = rs.referrersWithTrickleEarnings() == 0 ? 0 : rs.totalReferralTrickleExc() / rs.referrersWithTrickleEarnings();
        out.add(L("ref_avg", "  средний доход реферера", "EXC", (double) avgIncome, null, "мотивирует ли механика приводить людей", false));
    }

    private void sourcesTab(Period p, Period pv, List<Line> out, List<String> extras) {
        long fresh = userRepo.countRegisteredBetween(p.from(), p.to());
        long ads = userRepo.countByTrafficSourceCodeIsNotNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(p.from(), p.to());
        long adsPrev = userRepo.countByTrafficSourceCodeIsNotNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(pv.from(), pv.to());
        long ref = userRepo.countByReferredByTelegramIdIsNotNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(p.from(), p.to());
        out.add(L("src_ads", "Приток по рекламным ссылкам", "", (double) ads, (double) adsPrev));
        out.add(L("src_ref", "Приток по рефералке", "", (double) ref, null));
        out.add(L("src_org", "Органика (остальные)", "", (double) Math.max(0, fresh - ads - ref), null));
        List<TrafficFunnelService.SourceFunnel> all = funnelService.compute().stream()
                .filter(f -> f.started() > 0 || f.source().getSpendRub() > 0)
                .sorted((a, b) -> Long.compare(b.started(), a.started())).toList();
        long spend = 0, first = 0, second = 0, act = 0;
        for (var f : all) {
            spend += f.source().getSpendRub();
            first += f.firstQuest();
            second += f.secondQuest();
            act += f.activated();
        }
        out.add(L("src_spend", "Расход на закупы (введён вручную)", "₽", (double) spend, null));
        out.add(L("src_cac_act", "Стоимость активного игрока (CAC)", "₽", act > 0 && spend > 0 ? (double) Math.round((double) spend / act) : null, null,
                "расход / активировавшие аккаунт", true));
        out.add(L("src_cac_first", "Стоимость игрока с 1-м квестом", "₽", first > 0 && spend > 0 ? (double) Math.round((double) spend / first) : null, null, null, true));
        out.add(L("src_cac_second", "Стоимость игрока со 2-м квестом", "₽", second > 0 && spend > 0 ? (double) Math.round((double) spend / second) : null, null, null, true));
        StringBuilder sb = new StringBuilder("<b>Крупнейшие источники (все игроки с меткой)</b>\n");
        int shown = 0;
        for (var f : all) {
            if (shown++ >= 6) break;
            sb.append("• <code>").append(f.source().getCode()).append("</code> ").append(escape(f.source().getName()))
              .append(": зашли ").append(f.started()).append(" → 1-й квест ").append(f.firstQuest())
              .append(" (").append(f.started() > 0 ? Math.round(pct(f.firstQuest(), f.started())) : 0).append("%)");
            if (f.source().getSpendRub() > 0) sb.append(", ").append(f.source().getSpendRub()).append(" ₽");
            sb.append("\n");
        }
        if (all.isEmpty()) sb.append("Источников с заходами пока нет.\n");
        sb.append("\nПолное сравнение с ценой шага воронки: «📈 Трафик → 📊 Сравнение закупов».");
        extras.add(sb.toString());
    }

    private void pnlTab(Period p, Period pv, List<Line> out, List<String> extras) {
        LocalDate from = p.from().toLocalDate();
        LocalDate to = p.to().minusNanos(1).toLocalDate();
        LocalDate pfrom = pv.from().toLocalDate();
        LocalDate pto = pv.to().minusNanos(1).toLocalDate();
        long direct = financeService.sumByKind(FinanceEntry.DIRECT_ADS, from, to);
        long yandex = financeService.sumByKind(FinanceEntry.YANDEX_RSYA, from, to);
        long telega = financeService.sumByKind(FinanceEntry.TELEGA, from, to);
        long other = financeService.sumByKind(FinanceEntry.OTHER, from, to);
        long sponsor = financeService.sumByKind(FinanceEntry.SPONSOR, from, to);
        long sponsorP = financeService.sumByKind(FinanceEntry.SPONSOR, pfrom, pto);
        long directP = financeService.sumByKind(FinanceEntry.DIRECT_ADS, pfrom, pto);
        long yandexP = financeService.sumByKind(FinanceEntry.YANDEX_RSYA, pfrom, pto);
        long telegaP = financeService.sumByKind(FinanceEntry.TELEGA, pfrom, pto);
        long otherP = financeService.sumByKind(FinanceEntry.OTHER, pfrom, pto);
        out.add(L("rev_direct", "Доход: прямая реклама", "₽", (double) direct, (double) directP, "комиссия с закрытых сделок через менеджера (вводится вручную)", false));
        out.add(L("rev_yandex", "Доход: Yandex РСЯ", "₽", (double) yandex, (double) yandexP, "из кабинета РСЯ (вводится вручную)", false));
        out.add(L("rev_telega", "Доход: Telega.io", "₽", (double) telega, (double) telegaP, "из кабинета Telega.io (вводится вручную)", false));
        out.add(L("rev_sponsor", "Доход: спонсоры", "₽", (double) sponsor, (double) sponsorP, "платежи спонсоров (кампании записываются сами)", false));
        out.add(L("rev_other", "Доход: прочее", "₽", (double) other, (double) otherP));
        long revenue = direct + yandex + telega + sponsor + other;
        long revenuePrev = directP + yandexP + telegaP + sponsorP + otherP;
        out.add(L("rev_total", "Доходы всего", "₽", (double) revenue, (double) revenuePrev));
        RewardService.WithdrawalPeriodStats cur = withdrawalsBetween(p.from(), p.to());
        RewardService.WithdrawalPeriodStats prev = withdrawalsBetween(pv.from(), pv.to());
        long payouts = cur.totalRub() + cur.totalTonRub();
        long payoutsPrev = prev.totalRub() + prev.totalTonRub();
        out.add(L("payouts", "Выплаты пользователям (₽ + GRAM в рублях)", "₽", (double) payouts, (double) payoutsPrev,
                "звёзды Telegram (" + cur.totalStars() + " ⭐) в итог не входят: их рублёвый курс не задан", true));
        java.util.Map<String, Long> expCur = expenseService.sumsByKind(from, to);
        java.util.Map<String, Long> expPrev = expenseService.sumsByKind(pfrom, pto);
        for (String k : ru.gamebot.platform.domain.model.ExpenseEntry.KINDS) {
            if (expCur.get(k) == 0 && expPrev.get(k) == 0) continue;
            out.add(L("exp_" + k.toLowerCase(), "Расход: " + ru.gamebot.platform.domain.model.ExpenseEntry.kindLabel(k).toLowerCase(), "₽",
                    (double) expCur.get(k), (double) expPrev.get(k), "вводится вручную («💼 Финансы» → «➖ Расход»)", true));
        }
        long expenses = expCur.values().stream().mapToLong(Long::longValue).sum();
        long expensesPrev = expPrev.values().stream().mapToLong(Long::longValue).sum();
        out.add(L("exp_total", "Расходы всего (без выплат игрокам)", "₽", (double) expenses, (double) expensesPrev, null, true));
        out.add(L("net", "ИТОГ: доходы минус выплаты и расходы", "₽", (double) (revenue - payouts - expenses), (double) (revenuePrev - payoutsPrev - expensesPrev),
                "прибыль (+) или убыток (−) проекта за период", false));
        StringBuilder sb = new StringBuilder("<b>Записи о доходах за период</b>\n");
        List<FinanceEntry> entries = financeService.between(from, to);
        if (entries.isEmpty()) sb.append("Записей нет: добавьте доходы кнопкой «➕ Записать доход».\n");
        int i = 0;
        for (FinanceEntry e : entries) {
            if (i++ >= 10) break;
            sb.append("• ").append(e.getEntryDate().format(DateTimeFormatter.ofPattern("dd.MM"))).append(" · ")
              .append(FinanceEntry.kindLabel(e.getKind())).append(": ").append(fmtLong(e.getAmountRub())).append(" ₽");
            if (e.getNote() != null) sb.append(" (").append(escape(e.getNote())).append(")");
            sb.append("\n");
        }
        extras.add(sb.toString());
    }

    /** Вкладка «Курс дня и красная кнопка» (этап 4 ТЗ): цифры считает CourseAnalyticsService. Просмотры поста Bot API не отдаёт - есть только нажатия на кнопку. */
    private void courseTab(Period p, Period pv, List<Line> out, List<String> extras) {
        CourseAnalyticsService.Report r = courseAnalytics.report(p.from(), p.to());
        CourseAnalyticsService.Report q = courseAnalytics.report(pv.from(), pv.to());
        out.add(L("c_posts_d", "Постов «Курс дня»", "", (double) r.dailyPosts(), (double) q.dailyPosts(), null, false));
        out.add(L("c_posts_r", "Красных кнопок", "", (double) r.redPosts(), (double) q.redPosts(), null, false));
        out.add(L("c_clicks", "Нажатий на кнопку под постом", "", (double) r.clicks(), (double) q.clicks(), "открыли бота по кнопке, с повторами и до проверки подписки", false));
        out.add(L("c_act", "Активаций бонуса", "", (double) r.activations(), (double) q.activations(),
                "курс дня: " + r.activationsDaily() + ", красная: " + r.activationsRed(), false));
        out.add(L("c_users", "Игроков активировали", "", (double) r.activatingUsers(), (double) q.activatingUsers(), null, false));
        out.add(L("c_conv", "Конверсия нажатие → активация", "%", r.clicks() == 0 ? null : r.activationsOfPublished() * 100.0 / r.clicks(),
                q.clicks() == 0 ? null : q.activationsOfPublished() * 100.0 / q.clicks(), "активации постов периода / нажатия по ним; потеря в основном на проверке подписки", false));
        out.add(L("c_dau", "Активации к DAU (в среднем за день)", "%", r.activationsPerDauPct(), q.activationsPerDauPct(), "уникальные игроки с активацией / активные в журнале в тот же день", false));
        out.add(L("c_ret_a", "Вернулись на след. день: активировавшие", "%", r.returnActivatedPct(), q.returnActivatedPct(),
                "выборка " + r.returnSample() + " (дни, у которых следующий день уже прошёл)", false));
        out.add(L("c_ret_b", "Вернулись на след. день: все активные в те же дни", "%", r.returnBasePct(), q.returnBasePct(), "база для сравнения: даёт ли бонус возврат сверх обычного", false));
        out.add(L("c_w_with", "Заявок на вывод с бонусом", "", (double) r.withdrawalsWithBonus(), (double) q.withdrawalsWithBonus(), "рубли и TON", false));
        out.add(L("c_w_without", "Заявок на вывод без бонуса", "", (double) r.withdrawalsWithout(), (double) q.withdrawalsWithout(), null, false));
        out.add(L("c_avg_with", "Средняя заявка с бонусом (с бонусом внутри)", "₽", r.avgRubWith(), q.avgRubWith(), null, false));
        out.add(L("c_avg_without", "Средняя заявка без бонуса", "₽", r.avgRubWithout(), q.avgRubWithout(), null, false));
        out.add(L("c_paid", "Бонус в заявках (резерв + выплачено)", "₽", (double) r.bonusPaidRub(), (double) q.bonusPaidRub(), "без отменённых и отклонённых заявок", true));
        out.add(L("c_budget", "Бюджет бонусов: использовано из выделенного", "₽", (double) r.budgetUsedRub(), (double) q.budgetUsedRub(),
                "выделено " + r.budgetTotalRub() + " ₽ на опубликованные в периоде посты", true));
        out.add(L("c_react_a", "Красная кнопка: реакция, среднее", "мин", r.redReactionAvgMin(), q.redReactionAvgMin(), "минут от публикации до нажатия", false));
        out.add(L("c_react_m", "Красная кнопка: реакция, медиана", "мин", r.redReactionMedianMin(), q.redReactionMedianMin(), null, false));
        out.add(L("c_fraud_a", "Антифрод: активации у помеченных или заблокированных", "", (double) r.suspectActivations(), (double) q.suspectActivations(), "помечены уже после активации; ожидаемо 0", true));
        out.add(L("c_fraud_p", "Антифрод: бонус в заявках таких игроков", "₽", (double) r.bonusToSuspectsRub(), (double) q.bonusToSuspectsRub(), "если больше 0 - проверьте этих игроков и отклоните заявки", true));
        if (!r.perBonusLines().isEmpty()) {
            extras.add("<b>Последние кнопки периода</b>\n" + String.join("\n", r.perBonusLines()));
        }
        extras.add("<i>Просмотры поста недоступны: Telegram не отдаёт боту счётчик просмотров канала. Возврат на следующий день и активации к DAU считаются по журналу активных дней (с момента его запуска).</i>");
    }

    /** Вкладка «Витрина проекта» (22.10.2026): шесть ключевых метрик в одном месте. Это ДУБЛИ показателей из других вкладок («Удержание», «Вовлечённость», «Пользователи и приток»,
     *  «Финансовая сводка», «Фрод»), которые остаются на своих местах без изменений; считаются тем же кодом и теми же сервисами. Период влияет на пункты 4 и 5. */
    private void kpiTab(Period p, Period pv, LocalDate fromDate, List<Line> out, List<String> extras) {
        // 1. D1 / D7 / D30 (когорты по журналу активных дней, как во вкладке «Удержание»)
        RetentionService.Report rr = retentionService.report();
        out.add(L("k_d1", "1. Возврат D1 (4 зрелые недели)", "%", pctOrNull(rr.recentD1()), pctOrNull(rr.prevD1()), "против предыдущих 4 зрелых недель; период не выбирается", false));
        out.add(L("k_d7", "   Возврат D7 (4 зрелые недели)", "%", pctOrNull(rr.recentD7()), pctOrNull(rr.prevD7()), null, false));
        out.add(L("k_d30", "   Возврат D30 (4 зрелые недели)", "%", pctOrNull(rr.recentD30()), pctOrNull(rr.prevD30()), null, false));

        // 2. DAU / MAU и 3. доля новичков со вторым квестом (как во вкладке «Вовлечённость»)
        UserService.EngagementReport er = userService.getEngagementReport(null);
        Double dauPrev = snapVal(fromDate, PlatformSnapshot::getDau);
        Double mauPrev = snapVal(fromDate, PlatformSnapshot::getMau);
        out.add(L("k_dau", "2. DAU (активны за 24 ч)", "", (double) er.dau(), dauPrev));
        out.add(L("k_mau", "   MAU (активны за 30 дней)", "", (double) er.mau(), mauPrev));
        Double ratioPrev = (dauPrev != null && mauPrev != null && mauPrev > 0) ? dauPrev * 100.0 / mauPrev : null;
        out.add(L("k_dau_mau", "   DAU / MAU", "%", er.dauMauPercent(), ratioPrev, "норма: 10-20% окей, 20%+ хорошо, 25-30%+ уровень топ-игр", false));
        out.add(L("k_second", "3. Доля новичков, сделавших 2-й квест", "%", er.retentionPercent(), snapVal(fromDate, PlatformSnapshot::getSecondQuestReturnPct),
                "из когорты «1-й квест ≥7 дн. назад»; в когорте " + er.retentionCohort() + " чел.", false));

        // 4. Доля органики и рефералов среди новых за период (как «Новых за период» во вкладке «Пользователи и приток»)
        long fresh = userRepo.countRegisteredBetween(p.from(), p.to());
        long freshPrev = userRepo.countRegisteredBetween(pv.from(), pv.to());
        long ads = userRepo.countByTrafficSourceCodeIsNotNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(p.from(), p.to());
        long ref = userRepo.countByReferredByTelegramIdIsNotNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(p.from(), p.to());
        long adsPrev = userRepo.countByTrafficSourceCodeIsNotNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(pv.from(), pv.to());
        long refPrev = userRepo.countByReferredByTelegramIdIsNotNullAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(pv.from(), pv.to());
        long organic = Math.max(0, fresh - ads - ref);
        long organicPrev = Math.max(0, freshPrev - adsPrev - refPrev);
        out.add(L("k_new", "4. Новых игроков за период", "", (double) fresh, (double) freshPrev));
        out.add(L("k_org", "   Доля органики среди новых", "%", fresh == 0 ? null : pct(organic, fresh), freshPrev == 0 ? null : pct(organicPrev, freshPrev),
                "органика = без рекламной ссылки и без реферала", false));
        out.add(L("k_ref", "   Доля рефералов среди новых", "%", fresh == 0 ? null : pct(ref, fresh), freshPrev == 0 ? null : pct(refPrev, freshPrev), null, false));
        out.add(L("k_ads", "   Доля рекламных ссылок среди новых", "%", fresh == 0 ? null : pct(ads, fresh), freshPrev == 0 ? null : pct(adsPrev, freshPrev), null, false));

        // 5. Маржа: доход минус выплаты (доходы и выплаты считаются как во вкладке «Финансовая сводка»; расходы не вычитаются)
        LocalDate from = p.from().toLocalDate();
        LocalDate to = p.to().minusNanos(1).toLocalDate();
        LocalDate pfrom = pv.from().toLocalDate();
        LocalDate pto = pv.to().minusNanos(1).toLocalDate();
        long revenue = revenueBetween(from, to);
        long revenuePrev = revenueBetween(pfrom, pto);
        RewardService.WithdrawalPeriodStats cur = withdrawalsBetween(p.from(), p.to());
        RewardService.WithdrawalPeriodStats prev = withdrawalsBetween(pv.from(), pv.to());
        long payouts = cur.totalRub() + cur.totalTonRub();
        long payoutsPrev = prev.totalRub() + prev.totalTonRub();
        out.add(L("k_rev", "5. Доходы за период", "₽", (double) revenue, (double) revenuePrev, "то, что внесено в «Финансы» (реклама, спонсоры, прочее)", false));
        out.add(L("k_pay", "   Выплаты игрокам", "₽", (double) payouts, (double) payoutsPrev, "рубли + GRAM в рублях; звёзды не входят", true));
        out.add(L("k_margin", "   Маржа: доход минус выплаты", "₽", (double) (revenue - payouts), (double) (revenuePrev - payoutsPrev), "расходы (закупы, сервисы) здесь не вычитаются, итог с ними - во вкладке «Финансовая сводка»", false));
        out.add(L("k_margin_pct", "   Маржа, % от дохода", "%", revenue == 0 ? null : (revenue - payouts) * 100.0 / revenue,
                revenuePrev == 0 ? null : (revenuePrev - payoutsPrev) * 100.0 / revenuePrev, null, false));

        // 6. Доля фрода: заблокированные и помеченные подозрительными среди зарегистрированных
        long total = Math.max(1, userService.totalRegisteredUsers());
        long flagged = userRepo.countFraudOrBlocked();
        out.add(L("k_fraud", "6. Доля фрода среди игроков", "%", pct(flagged, total), null,
                "заблокированные или помеченные подозрительными: " + flagged + " из " + total + " (подозрительных " + userRepo.countFraudSuspects()
                        + ", заблокированных " + userRepo.countByBlockedTrue() + "); считается на сейчас, не за период", true));
        extras.add("<i>Все цифры повторяют показатели других вкладок («Удержание», «Вовлечённость», «Пользователи и приток», «Финансовая сводка», «Фрод»): там они остались как были.</i>");
    }

    private long revenueBetween(LocalDate from, LocalDate to) {
        return financeService.sumByKind(FinanceEntry.DIRECT_ADS, from, to) + financeService.sumByKind(FinanceEntry.YANDEX_RSYA, from, to)
                + financeService.sumByKind(FinanceEntry.TELEGA, from, to) + financeService.sumByKind(FinanceEntry.SPONSOR, from, to)
                + financeService.sumByKind(FinanceEntry.OTHER, from, to);
    }

    /** Вкладка «EGC Pass: продажи» (23.10.2026): воронка показ → открытие экрана → покупка, продления и число владельцев сейчас. */
    private void egcPassTab(Period p, Period pv, List<Line> out, List<String> extras) {
        EgcPassFunnelService.Funnel f = egcPassFunnel.funnel(p.from(), p.to());
        EgcPassFunnelService.Funnel q = egcPassFunnel.funnel(pv.from(), pv.to());
        long buyers = f.firstPurchases() + f.trialPurchases();
        long buyersPrev = q.firstPurchases() + q.trialPurchases();
        out.add(L("p_active", "Владельцев Pass сейчас", "", (double) userRepo.countEgcPassActive(LocalDateTime.now()), null, "подписка или пробный месяц ещё действуют", false));
        out.add(L("p_offers", "Показов предложения", "", (double) f.offers(), (double) q.offers(), "уникальных игроков: " + f.offerUsers(), false));
        out.add(L("p_opens", "Открытий экрана Pass", "", (double) f.opens(), (double) q.opens(), "уникальных игроков: " + f.openUsers(), false));
        out.add(L("p_first", "Новых покупок (платных)", "", (double) f.firstPurchases(), (double) q.firstPurchases(), null, false));
        out.add(L("p_trial", "Пробных месяцев", "", (double) f.trialPurchases(), (double) q.trialPurchases(), null, false));
        out.add(L("p_renew", "Продлений", "", (double) f.renewals(), (double) q.renewals(), "главный показатель ценности: покупают ли второй раз", false));
        out.add(L("p_conv", "Конверсия: открыли экран → купили", "%", f.openUsers() == 0 ? null : Math.min(100.0, buyers * 100.0 / f.openUsers()),
                q.openUsers() == 0 ? null : Math.min(100.0, buyersPrev * 100.0 / q.openUsers()), "новые покупки и пробные / уникальные открывшие экран", false));
        StringBuilder sb = new StringBuilder("<b>Откуда показы предложения</b>\n");
        if (f.bySource().isEmpty()) sb.append("Показов за период нет.\n");
        for (Object[] r : f.bySource()) sb.append("• ").append(escape(String.valueOf(r[0]))).append(": ").append(r[1]).append(" (игроков ").append(r[2]).append(")\n");
        extras.add(sb.toString());
        extras.add("<i>Показы и открытия считаются с момента добавления вкладки (23.10.2026); покупки и продления берутся из журнала Stars за весь период.</i>");
    }

    private void tournamentsTab(List<Line> out, List<String> extras) {
        List<Tournament> all = tournamentRepo.findAllByOrderByCreatedAtDesc();
        long totalEntries = 0, totalPool = 0;
        java.util.Map<String, long[]> byGame = new java.util.TreeMap<>(); // игра -> {турниров, участников, фонд, дошли до конца, зарегистрировались}
        StringBuilder sb = new StringBuilder("<b>Последние турниры</b>\n");
        int shown = 0;
        for (Tournament t : all) {
            if (t.getStatus() == Tournament.Status.CANCELLED_LOW_TURNOUT && t.getPrizePoolExc() == 0) continue;
            List<TournamentEntry> entries = entryRepo.findAllWithUserByTournamentUnordered(t);
            long registered = entries.size();
            long finished = entries.stream().filter(e -> !e.isDisqualified() && e.getTrophiesStart() != null && e.getTrophiesEnd() != null).count();
            boolean trophy = t.getScoringType().isTrophyRace();
            String game = t.getGameName() != null ? t.getGameName() : "без игры";
            long[] g = byGame.computeIfAbsent(game, k -> new long[5]);
            g[0]++;
            g[1] += registered;
            g[2] += t.getPrizePoolExc();
            totalEntries += registered;
            totalPool += t.getPrizePoolExc();
            if (trophy && t.getStatus() == Tournament.Status.FINISHED) {
                g[3] += finished;
                g[4] += registered;
            }
            if (shown++ < 6) {
                sb.append("• ").append(escape(t.getName())).append(" · ").append(escape(game)).append(" · ")
                  .append(switch (t.getStatus()) {
                      case REGISTRATION -> "регистрация";
                      case ACTIVE -> "идёт";
                      case FINISHED -> "завершён";
                      case CANCELLED_LOW_TURNOUT -> "отменён";
                  }).append(": участников ").append(registered).append(", фонд ").append(fmtLong(t.getPrizePoolExc())).append(" EXC");
                if (trophy && t.getStatus() == Tournament.Status.FINISHED && registered > 0) {
                    sb.append(", дошли до конца ").append(Math.round(pct(finished, registered))).append("%");
                }
                sb.append("\n");
            }
        }
        out.add(L("t_count", "Турниров проведено (без пустых отмен)", "", (double) byGame.values().stream().mapToLong(a -> a[0]).sum(), null));
        out.add(L("t_entries", "Участников всего", "", (double) totalEntries, null, "охват механики турниров", false));
        out.add(L("t_pool", "Призовой фонд суммарно", "EXC", (double) totalPool, null));
        long fin = byGame.values().stream().mapToLong(a -> a[3]).sum();
        long reg = byGame.values().stream().mapToLong(a -> a[4]).sum();
        out.add(L("t_complete", "Завершаемость трофи-турниров", "%", reg > 0 ? pct(fin, reg) : null, null,
                "доля участников, у которых есть и старт, и финиш без дисквалификации", false));
        if (!byGame.isEmpty()) {
            sb.append("\n<b>Разбивка по играм</b>\n");
            for (var e : byGame.entrySet()) {
                long[] g = e.getValue();
                sb.append("• ").append(escape(e.getKey())).append(": турниров ").append(g[0]).append(", участников ").append(g[1])
                  .append(", фонд ").append(fmtLong(g[2])).append(" EXC");
                if (g[4] > 0) sb.append(", завершаемость ").append(Math.round(pct(g[3], g[4]))).append("%");
                sb.append("\n");
            }
        } else {
            sb.append("Турниров пока не было.\n");
        }
        extras.add(sb.toString());
    }

    private void techTab(Period p, Period pv, List<Line> out, List<String> extras) {
        Double avg = avgReviewMinutes(p.from(), p.to());
        Double avgPrev = avgReviewMinutes(pv.from(), pv.to());
        out.add(L("review", "Среднее время проверки заявки", "мин", avg, avgPrev, "от отправки до решения модератора; долгое ожидание гасит мотивацию", true));
        Double up = heartbeatService.uptimePercent(p.from(), p.to());
        Double upPrev = heartbeatService.uptimePercent(pv.from(), pv.to());
        out.add(L("uptime", "Аптайм бота", "%", up, upPrev,
                up == null ? "отметки «жив» копятся с момента запуска этой версии" : "доля времени онлайн по отметкам раз в 5 минут", false));
        var sum = errorMonitor.summarize(24);
        out.add(L("errors24", "Ошибок в логах за 24 ч", "", (double) sum.errors(), null, "подробности - «🩺 Проверка ошибок»", true));
        out.add(L("warns24", "Предупреждений за 24 ч", "", (double) sum.warns(), null, null, true));
        List<IncidentEntry> open = incidentRepo.findAllByStatusOrderByCreatedAtDesc(IncidentEntry.OPEN);
        out.add(L("incidents", "Открытых багов/инцидентов", "", (double) open.size(), null, "ручной трекер: список ниже", true));
        StringBuilder sb = new StringBuilder("<b>Открытые баги и инциденты</b>\n");
        if (open.isEmpty()) sb.append("Открытых нет.\n");
        List<IncidentEntry> sorted = new ArrayList<>(open);
        sorted.sort((a, b) -> Integer.compare(prioRank(a.getPriority()), prioRank(b.getPriority())));
        for (IncidentEntry e : sorted) {
            sb.append(IncidentEntry.priorityIcon(e.getPriority())).append(" #").append(e.getId()).append(" ").append(escape(e.getTitle()))
              .append(" (с ").append(e.getCreatedAt().format(DateTimeFormatter.ofPattern("dd.MM"))).append(")\n");
        }
        extras.add(sb.toString());
    }

    private static int prioRank(String p) {
        return "HIGH".equals(p) ? 0 : "MEDIUM".equals(p) ? 1 : 2;
    }

    private void fraudTab(Period p, List<Line> out, List<String> extras) {
        out.add(L("blocked", "Заблокировано всего", "", (double) userRepo.countByBlockedTrue(), null, "эффективность модерации; блокировок «за период» без даты в базе не видно", false));
        out.add(L("dup_photos", "Заявок с повторным фото за период", "", (double) submissionRepo.countDuplicatePhotosBetween(p.from(), p.to()), null,
                "одно и то же изображение в разных заявках", true));
        out.add(L("t_anomalies", "Подозрительных заявок в турнирах (открытых)", "", (double) entryRepo.countByAnomalyFlagTrueAndAnomalyResolvedFalse(), null,
                "разбор - «🏆 Турниры → Аномалии»", true));
        List<Object[]> perUser = submissionRepo.findApprovalsPerUserBetween(p.from(), p.to());
        double mean = perUser.stream().mapToLong(r -> ((Number) r[2]).longValue()).average().orElse(0);
        double var = perUser.stream().mapToDouble(r -> Math.pow(((Number) r[2]).longValue() - mean, 2)).average().orElse(0);
        double limit = mean + 3 * Math.sqrt(var);
        long outliers = perUser.stream().filter(r -> ((Number) r[2]).longValue() > limit && ((Number) r[2]).longValue() >= 20).count();
        out.add(L("outliers", "Аккаунтов с аномальной скоростью выполнения", "", (double) outliers, null,
                "больше среднего + 3 отклонения и не меньше 20 квестов за период", true));
        StringBuilder sb = new StringBuilder("<b>Больше всего выполнений за период</b>\n");
        int i = 0;
        for (Object[] r : perUser) {
            if (i++ >= 5) break;
            long n = ((Number) r[2]).longValue();
            sb.append("• ").append(escape(String.valueOf(r[1]))).append(" (<code>").append(r[0]).append("</code>): ").append(n)
              .append(n > limit && n >= 20 ? " ⚠️" : "").append("\n");
        }
        if (perUser.isEmpty()) sb.append("Выполнений за период нет.\n");
        sb.append("\n<i>Дубликаты аккаунтов по устройству/IP не считаются: платформа эти данные не собирает. Повторы разовых квестов - «🕵️ Повторы разовых квестов».</i>");
        extras.add(sb.toString());
    }

    // ───────────────────────── тренды ─────────────────────────

    private record TrendMetric(String label, Function<PlatformSnapshot, Long> f) {}

    private List<TrendMetric> trendMetrics(Tab tab) {
        return switch (tab) {
            case USERS -> List.of(new TrendMetric("Всего игроков", PlatformSnapshot::getTotalUsers), new TrendMetric("Новых за 7 дней", PlatformSnapshot::getNewUsersWeek));
            case ACTIVITY -> List.of(new TrendMetric("Активны за 7 дней", PlatformSnapshot::getActive7Days), new TrendMetric("Активны за 30 дней", PlatformSnapshot::getActive30Days),
                    new TrendMetric("Возврат 7 дн., %", PlatformSnapshot::getRetention7Pct), new TrendMetric("Возврат 30 дн., %", PlatformSnapshot::getRetention30Pct));
            case ENGAGEMENT -> List.of(new TrendMetric("DAU", PlatformSnapshot::getDau), new TrendMetric("MAU", PlatformSnapshot::getMau),
                    new TrendMetric("Взяли квест за 7 дн.", PlatformSnapshot::getQuestTakers7d), new TrendMetric("Возврат за 2-м квестом, %", PlatformSnapshot::getSecondQuestReturnPct));
            case QUESTS -> List.of(new TrendMetric("Выполнено за 30 дней", PlatformSnapshot::getApprovedQuestsMonth), new TrendMetric("Активных квестов", PlatformSnapshot::getActiveQuestsCount),
                    new TrendMetric("На модерации", PlatformSnapshot::getPendingSubmissions), new TrendMetric("Ср. время проверки, мин", PlatformSnapshot::getAvgReviewMin7d));
            case ECONOMY -> List.of(new TrendMetric("Начислено за 7 дн., EXC", PlatformSnapshot::getEarnedExc7d), new TrendMetric("Выплачено за 7 дн., EXC", PlatformSnapshot::getPaidOutExc7d),
                    new TrendMetric("EXC на счетах", PlatformSnapshot::getTotalCoinsOnAccounts), new TrendMetric("Билетов в обороте", PlatformSnapshot::getTotalTickets));
            case REFERRAL -> List.of(new TrendMetric("Всего рефералов", PlatformSnapshot::getReferralsTotal), new TrendMetric("Активировано", PlatformSnapshot::getReferralsActivated));
            case SQUADS -> List.of(new TrendMetric("Игроков в отрядах", PlatformSnapshot::getPlayersInSquads), new TrendMetric("Отрядов", PlatformSnapshot::getSquadsCount),
                    new TrendMetric("DAU/MAU в отряде, %", PlatformSnapshot::getDauMauInSquadPct), new TrendMetric("DAU/MAU без отряда, %", PlatformSnapshot::getDauMauNoSquadPct),
                    new TrendMetric("Возврат за 2-й квест в отряде, %", PlatformSnapshot::getSecondQuestReturnInSquadPct), new TrendMetric("Возврат за 2-й квест без отряда, %", PlatformSnapshot::getSecondQuestReturnNoSquadPct));
            case PACKS -> List.of(new TrendMetric("DAU/MAU: ротируемые игры, %", PlatformSnapshot::getDauMauRotatingPct), new TrendMetric("DAU/MAU: контроль, %", PlatformSnapshot::getDauMauControlPct));
            case WITHDRAWALS -> List.of(new TrendMetric("Выплачено за 7 дн., EXC", PlatformSnapshot::getPaidOutExc7d), new TrendMetric("Получателей выплат", PlatformSnapshot::getUniqueWithdrawalRecipients));
            case TECH -> List.of(new TrendMetric("Аптайм за 7 дн., ‰", PlatformSnapshot::getUptimePermille7d), new TrendMetric("Ср. время проверки, мин", PlatformSnapshot::getAvgReviewMin7d));
            case FRAUD -> List.of(new TrendMetric("Заблокировано", PlatformSnapshot::getBlockedUsers));
            case KPI -> List.of(new TrendMetric("DAU", PlatformSnapshot::getDau), new TrendMetric("MAU", PlatformSnapshot::getMau),
                    new TrendMetric("Возврат за 2-м квестом, %", PlatformSnapshot::getSecondQuestReturnPct), new TrendMetric("Заблокировано", PlatformSnapshot::getBlockedUsers));
            default -> List.of();
        };
    }

    /** Тренд ключевых метрик вкладки за days дней из ежедневных снимков: строка-график и «первое → последнее (изменение)». */
    public String trend(Tab tab, int days) {
        List<TrendMetric> metrics = trendMetrics(tab);
        StringBuilder sb = new StringBuilder("📈 <b>Тренд: ").append(tab.title).append("</b> · ").append(days).append(" дней\n\n");
        if (metrics.isEmpty()) {
            return sb.append("Для этой вкладки тренд не строится: значения считаются по запросу, а не из ежедневных снимков.").toString();
        }
        List<PlatformSnapshot> snaps = snapRepo.findAllBySnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(LocalDate.now().minusDays(days));
        if (snaps.size() < 2) {
            return sb.append("Снимков пока мало (ежедневный снимок делается в 00:05 UTC): график появится через пару дней.").toString();
        }
        for (TrendMetric m : metrics) {
            List<Double> series = new ArrayList<>();
            for (PlatformSnapshot s : snaps) {
                Long v = m.f().apply(s);
                if (v != null) series.add(v.doubleValue());
            }
            sb.append("<b>").append(m.label()).append("</b>\n");
            if (series.size() < 2) {
                sb.append("<i>данных пока нет (метрика добавлена недавно)</i>\n\n");
                continue;
            }
            double first = series.get(0);
            double last = series.get(series.size() - 1);
            sb.append("<code>").append(sparkline(series, 30)).append("</code>\n")
              .append(fmtDouble(first)).append(" → <b>").append(fmtDouble(last)).append("</b> ").append(deltaText(last, first, "", false)).append("\n\n");
        }
        sb.append("<i>Точек: ").append(snaps.size()).append(". У новых метрик история начинается с момента их добавления.</i>");
        return sb.toString();
    }

    private static String sparkline(List<Double> values, int maxPoints) {
        List<Double> v = values;
        if (v.size() > maxPoints) {
            List<Double> sampled = new ArrayList<>();
            for (int i = 0; i < maxPoints; i++) sampled.add(v.get((int) Math.round(i * (v.size() - 1) / (double) (maxPoints - 1))));
            v = sampled;
        }
        double min = v.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = v.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        String bars = "▁▂▃▄▅▆▇█";
        StringBuilder sb = new StringBuilder();
        for (double x : v) {
            int idx = max == min ? 3 : (int) Math.round((x - min) / (max - min) * (bars.length() - 1));
            sb.append(bars.charAt(idx));
        }
        return sb.toString();
    }

    // ───────────────────────── форматирование ─────────────────────────

    public String format(TabData d, boolean compare) {
        StringBuilder sb = new StringBuilder();
        sb.append(d.tab().icon).append(" <b>").append(d.tab().title).append("</b>\n");
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd.MM HH:mm");
        if (d.tab() == Tab.RETENTION) {
            sb.append("<i>Когортная вкладка: период не выбирается, расчёт обновляется раз в час</i>\n\n");
        } else {
            sb.append("<i>Период: ").append(d.period().label()).append(" (").append(d.period().from().format(f)).append(" - ")
              .append(d.period().to().format(f)).append(" UTC)");
            if (compare) sb.append(", сравнение с предыдущим окном такой же длины");
            sb.append("</i>\n\n");
        }
        for (Line l : d.lines()) {
            sb.append(l.label()).append(": <b>").append(valueText(l.value(), l.unit())).append("</b>");
            if (compare && l.prev() != null && l.value() != null) sb.append(" ").append(deltaText(l.value(), l.prev(), l.unit(), l.lowerIsBetter()));
            sb.append("\n");
            if (l.note() != null) sb.append("   <i>").append(l.note()).append("</i>\n");
        }
        for (String e : d.extras()) sb.append("\n").append(e).append("\n");
        return sb.toString();
    }

    private static String valueText(Double v, String unit) {
        if (v == null) return "—";
        String n = fmtDouble(v);
        return unit == null || unit.isEmpty() ? n : n + " " + unit;
    }

    static String fmtDouble(double v) {
        return v == Math.rint(v) ? fmtLong((long) v) : String.format(RU, "%,.1f", v);
    }

    static String fmtLong(long v) {
        return String.format(RU, "%,d", v);
    }

    /** ▲/▼ + абсолютное изменение и процент; для метрик в процентах - в процентных пунктах. */
    static String deltaText(double cur, double prev, String unit, boolean lowerIsBetter) {
        double diff = cur - prev;
        if (Math.abs(diff) < 0.05) return "▬ без изменений";
        String arrow = diff > 0 ? "▲" : "▼";
        String sign = diff > 0 ? "+" : "−";
        String abs = fmtDouble(Math.abs(diff));
        if ("%".equals(unit)) return arrow + " " + sign + abs + " п.п.";
        String tail = prev != 0 ? " (" + sign + fmtDouble(Math.round(Math.abs(diff) / Math.abs(prev) * 1000) / 10.0) + "%)" : "";
        return arrow + " " + sign + abs + tail;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ───────────────────────── CSV ─────────────────────────

    private static String csvCell(String s) {
        if (s == null) return "";
        String v = s.replace("\"", "\"\"");
        return v.contains(";") || v.contains("\"") || v.contains("\n") ? "\"" + v + "\"" : v;
    }

    /** Сводная таблица вкладки; разделитель «;», в начале BOM - Excel открывает русский текст без «кракозябр». */
    public String summaryCsv(TabData d) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("Метрика;Единица;Текущий период;Предыдущий период;Изменение\n");
        for (Line l : d.lines()) {
            String cur = l.value() == null ? "" : String.valueOf(l.value());
            String prev = l.prev() == null ? "" : String.valueOf(l.prev());
            String diff = (l.value() == null || l.prev() == null) ? "" : String.valueOf(Math.round((l.value() - l.prev()) * 100) / 100.0);
            sb.append(csvCell(l.label().trim())).append(';').append(csvCell(l.unit())).append(';').append(cur).append(';').append(prev).append(';').append(diff).append('\n');
        }
        return sb.toString();
    }

    /** Сводка всех вкладок в одном CSV (для передачи в отдел аналитики): Вкладка;Метрика;Единица;Текущий период;Предыдущий период;Изменение. */
    public String fullSummaryCsv(List<TabData> all) {
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("Вкладка;Метрика;Единица;Текущий период;Предыдущий период;Изменение\n");
        for (TabData d : all) {
            for (Line l : d.lines()) {
                String cur = l.value() == null ? "" : String.valueOf(l.value());
                String prev = l.prev() == null ? "" : String.valueOf(l.prev());
                String diff = (l.value() == null || l.prev() == null) ? "" : String.valueOf(Math.round((l.value() - l.prev()) * 100) / 100.0);
                sb.append(csvCell(d.tab().title)).append(';').append(csvCell(l.label().trim())).append(';').append(csvCell(l.unit())).append(';')
                  .append(cur).append(';').append(prev).append(';').append(diff).append('\n');
            }
        }
        return sb.toString();
    }

    /** Сырые данные за период (для вкладок, где они осмысленны); null - для остальных вкладок выгружается только сводка. */
    public String rawCsv(Tab tab, Period p) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        StringBuilder sb = new StringBuilder("﻿");
        switch (tab) {
            case USERS -> {
                sb.append("telegram_id;никнейм;зарегистрирован;источник;страна\n");
                for (var u : userRepo.findAllByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(p.from(), p.to())) {
                    sb.append(u.getTelegramId()).append(';').append(csvCell(u.getNickname())).append(';')
                      .append(u.getCreatedAt() == null ? "" : u.getCreatedAt().format(f)).append(';')
                      .append(csvCell(u.getTrafficSourceCode() != null ? u.getTrafficSourceCode() : (u.getReferredByTelegramId() != null ? "referral" : "organic"))).append(';')
                      .append(csvCell(u.getCountry())).append('\n');
                }
            }
            case QUESTS -> {
                sb.append("id заявки;telegram_id;квест;игра;одобрена;EXC\n");
                for (Object[] r : submissionRepo.findApprovedRowsBetween(p.from(), p.to())) {
                    sb.append(r[0]).append(';').append(r[1]).append(';').append(csvCell(String.valueOf(r[2]))).append(';').append(csvCell(String.valueOf(r[3]))).append(';')
                      .append(r[4] == null ? "" : ((LocalDateTime) r[4]).format(f)).append(';').append(r[5] == null ? "" : r[5]).append('\n');
                }
            }
            case RETENTION -> {
                return retentionService.csv();
            }
            case ECONOMY -> {
                sb.append("дата;telegram_id;тип;сумма EXC\n");
                for (Object[] r : excRepo.findRowsBetween(p.from(), p.to())) {
                    sb.append(((LocalDateTime) r[0]).format(f)).append(';').append(r[1]).append(';').append(csvCell(String.valueOf(r[2]))).append(';').append(r[3]).append('\n');
                }
            }
            default -> {
                return null;
            }
        }
        return sb.toString();
    }
}
