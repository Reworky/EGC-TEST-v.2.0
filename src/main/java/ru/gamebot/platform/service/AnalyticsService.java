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
        QUESTS("🎯", "Квесты"),
        ECONOMY("💰", "Экономика и выплаты"),
        REFERRAL("🤝", "Реферальная программа"),
        SOURCES("📡", "Источники трафика"),
        PNL("📒", "Финансовая сводка"),
        TOURNAMENTS("🏆", "Турниры"),
        TECH("🛠", "Техническое здоровье"),
        FRAUD("🕵️", "Фрод и злоупотребления");

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
    private final TrafficFunnelService funnelService;
    private final FinanceService financeService;
    private final IncidentEntryRepository incidentRepo;
    private final HeartbeatService heartbeatService;
    private final ErrorMonitorService errorMonitor;

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
                case QUESTS -> questsTab(p, pv, fromDate, lines);
                case ECONOMY -> economyTab(p, pv, fromDate, lines);
                case REFERRAL -> referralTab(p, pv, fromDate, lines);
                case SOURCES -> sourcesTab(p, pv, lines, extras);
                case PNL -> pnlTab(p, pv, lines, extras);
                case TOURNAMENTS -> tournamentsTab(lines, extras);
                case TECH -> techTab(p, pv, lines, extras);
                case FRAUD -> fraudTab(p, lines, extras);
            }
        } catch (Exception e) {
            log.error("Analytics tab {} failed", tab, e);
            extras.add("⚠️ Часть данных не удалось посчитать, подробности в логе (раздел «Проверка ошибок»).");
        }
        return new TabData(tab, p, lines, extras);
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

    /** Выплаты в окне [from, to): разность двух накопительных «с момента» - других запросов по окну в сервисе выплат нет. */
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
        long other = financeService.sumByKind(FinanceEntry.OTHER, from, to);
        long directP = financeService.sumByKind(FinanceEntry.DIRECT_ADS, pfrom, pto);
        long yandexP = financeService.sumByKind(FinanceEntry.YANDEX_RSYA, pfrom, pto);
        long otherP = financeService.sumByKind(FinanceEntry.OTHER, pfrom, pto);
        out.add(L("rev_direct", "Доход: прямая реклама", "₽", (double) direct, (double) directP, "комиссия с закрытых сделок через менеджера (вводится вручную)", false));
        out.add(L("rev_yandex", "Доход: Yandex РСЯ", "₽", (double) yandex, (double) yandexP, "из кабинета РСЯ (вводится вручную)", false));
        out.add(L("rev_other", "Доход: прочее", "₽", (double) other, (double) otherP));
        long revenue = direct + yandex + other;
        long revenuePrev = directP + yandexP + otherP;
        out.add(L("rev_total", "Доходы всего", "₽", (double) revenue, (double) revenuePrev));
        RewardService.WithdrawalPeriodStats cur = withdrawalsBetween(p.from(), p.to());
        RewardService.WithdrawalPeriodStats prev = withdrawalsBetween(pv.from(), pv.to());
        long payouts = cur.totalRub() + cur.totalTonRub();
        long payoutsPrev = prev.totalRub() + prev.totalTonRub();
        out.add(L("payouts", "Выплаты пользователям (₽ + GRAM в рублях)", "₽", (double) payouts, (double) payoutsPrev,
                "звёзды Telegram (" + cur.totalStars() + " ⭐) в итог не входят: их рублёвый курс не задан", true));
        out.add(L("net", "ИТОГ: доходы минус выплаты", "₽", (double) (revenue - payouts), (double) (revenuePrev - payoutsPrev),
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
            case TECH -> List.of(new TrendMetric("Аптайм за 7 дн., ‰", PlatformSnapshot::getUptimePermille7d), new TrendMetric("Ср. время проверки, мин", PlatformSnapshot::getAvgReviewMin7d));
            case FRAUD -> List.of(new TrendMetric("Заблокировано", PlatformSnapshot::getBlockedUsers));
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
        sb.append("<i>Период: ").append(d.period().label()).append(" (").append(d.period().from().format(f)).append(" - ")
          .append(d.period().to().format(f)).append(" UTC)");
        if (compare) sb.append(", сравнение с предыдущим окном такой же длины");
        sb.append("</i>\n\n");
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
