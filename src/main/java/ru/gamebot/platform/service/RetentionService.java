package ru.gamebot.platform.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.model.TrafficSource;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.domain.repository.TrafficSourceRepository;
import ru.gamebot.platform.domain.repository.UserActivityDayRepository;

/**
 * Когортный возврат игроков D1 / D7 / D30 (ТЗ «аналитика удержания и источников», 2026-10-15).
 *
 * <p>ЕДИНЫЕ ОПРЕДЕЛЕНИЯ (все показатели вкладки «Удержание» считаются только так):
 * <ul>
 *   <li><b>День 0</b> - день регистрации игрока по Москве (дата createdAt игрока, у которого registrationCompleted).</li>
 *   <li><b>Активность</b> - строка журнала {@code user_activity_days}: любое действие игрока в боте или открытие мини-аппа (рассылки не считаются).</li>
 *   <li><b>Возврат на день N (DN)</b> - есть активность ровно в день «день 0 + N». <b>Возврат в окне (W7, W30)</b> - активность хотя бы в один день с 1-го по N-й.</li>
 *   <li><b>Зрелость</b> - день N считается завершённым, когда он строго раньше сегодняшнего дня по Москве. Недельная когорта (с понедельника)
 *       попадает в DN, только когда день N прошёл у ПОСЛЕДНЕГО зарегистрировавшегося в ней; иначе в таблице «—», а не ноль.</li>
 *   <li><b>Журнал</b> ведётся с первого дня записи (findFirstDay). Игроки, зарегистрированные раньше, в расчёт не входят (история не восстанавливается).</li>
 *   <li><b>Минимум</b> - при выборке меньше {@value #MIN_SAMPLE} процент не показывается («мало»).</li>
 * </ul>
 * Показатели вкладки «Отряды» считаются по другому определению («активен за последние N дней»), это отмечено в справке вкладки.
 *
 * <p>Расчёт кэшируется на час (данных мало: тысячи игроков, десятки тысяч строк журнала).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetentionService {

    public static final int MIN_SAMPLE = 30;
    private static final long CACHE_MS = 60 * 60 * 1000L;
    public static final String SRCTYPE_PREFIX = "retention.srctype.";
    private static final ZoneId MSK = ActivityDayService.MSK;

    private final AppUserRepository userRepo;
    private final UserActivityDayRepository dayRepo;
    private final QuestSubmissionRepository submissionRepo;
    private final TrafficSourceRepository sourceRepo;
    private final AppSettingRepository settingRepo;

    private Report cached;
    private long cachedAtMs;

    /** Доля вернувшихся. measurable = false: хотя бы у части когорты день N ещё не прошёл или не покрыт журналом (показываем «—»). */
    public record Rate(long n, long returned, boolean measurable) {
        public Integer percent() {
            return measurable && n >= MIN_SAMPLE ? (int) Math.round(returned * 100.0 / n) : null;
        }

        /** «—» - не измеримо, «мало» - выборка меньше минимума, иначе процент. */
        public String text() {
            if (!measurable) return "—";
            Integer p = percent();
            return p == null ? "мало" : p + "%";
        }
    }

    public record WeekRow(LocalDate weekStart, long size, Rate d1, Rate d7, Rate d30, Rate w7, Rate w30) {}

    public record SegmentRow(String label, long size, Rate d1, Rate d7, Rate d30) {}

    public record SourceCost(String code, String name, long spendRub, long registered, long n7, long returned7, long n30, long returned30) {}

    public record Report(LocalDate today, LocalDate journalStart, long cohortUsers, List<WeekRow> weeks,
                         Map<String, List<SegmentRow>> segments, List<SourceCost> costs,
                         Rate recentD1, Rate prevD1, Rate recentD7, Rate prevD7, Rate recentD30, Rate prevD30) {}

    private record Member(long id, LocalDate day0, String srcType, String game, String firstType, boolean inSquad, String srcCode) {}

    public synchronized void invalidate() {
        cached = null;
    }

    public synchronized Report report() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAtMs < CACHE_MS) return cached;
        cached = build();
        cachedAtMs = now;
        return cached;
    }

    private Report build() {
        LocalDate today = LocalDate.now(MSK);
        LocalDate journalStart = dayRepo.findFirstDay();

        Map<Long, Set<LocalDate>> days = new HashMap<>();
        for (Object[] r : dayRepo.findAllPairs()) {
            days.computeIfAbsent((Long) r[0], k -> new HashSet<>()).add((LocalDate) r[1]);
        }

        Map<Long, Quest> firstQuest = new HashMap<>();
        for (Object[] r : submissionRepo.findFirstSubmissionPerUser()) {
            firstQuest.putIfAbsent((Long) r[0], (Quest) r[1]);
        }

        List<TrafficSource> sources = sourceRepo.findAllByOrderByCreatedAtDesc();
        Map<String, TrafficSource> byCode = new HashMap<>();
        java.time.LocalDateTime sourcesSince = null;
        for (TrafficSource s : sources) {
            byCode.put(s.getCode(), s);
            if (sourcesSince == null || s.getCreatedAt().isBefore(sourcesSince)) sourcesSince = s.getCreatedAt();
        }

        List<Member> members = new ArrayList<>();
        for (AppUser u : userRepo.findAllByRegistrationCompletedTrueOrderByXpDescTelegramIdAsc()) {
            if (u.getCreatedAt() == null || u.getId() == null) continue;
            LocalDate day0 = u.getCreatedAt().atZone(ZoneId.systemDefault()).withZoneSameInstant(MSK).toLocalDate();
            Quest q = firstQuest.get(u.getId());
            members.add(new Member(u.getId(), day0, sourceType(u, byCode, sourcesSince), q == null ? "без квеста" : q.getGameName(),
                    q == null ? "без квеста" : methodLabel(ProofStatsService.verificationMethod(q)),
                    u.getSquadId() != null, u.getTrafficSourceCode()));
        }

        // Только игроки, у которых день 0 уже внутри журнала
        List<Member> cohort = new ArrayList<>();
        if (journalStart != null) {
            for (Member m : members) if (!m.day0().isBefore(journalStart)) cohort.add(m);
        }

        // Недельные когорты (с понедельника по МСК), последние 8 недель
        Map<LocalDate, List<Member>> byWeek = new LinkedHashMap<>();
        for (Member m : members) {
            byWeek.computeIfAbsent(m.day0().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), k -> new ArrayList<>()).add(m);
        }
        List<LocalDate> weekStarts = new ArrayList<>(byWeek.keySet());
        weekStarts.sort(Comparator.reverseOrder());
        List<WeekRow> weeks = new ArrayList<>();
        for (LocalDate ws : weekStarts.subList(0, Math.min(8, weekStarts.size()))) {
            List<Member> list = byWeek.get(ws);
            boolean inJournal = journalStart != null && !ws.isBefore(journalStart);
            LocalDate last = list.stream().map(Member::day0).max(LocalDate::compareTo).orElse(ws);
            weeks.add(new WeekRow(ws, list.size(),
                    weekRate(list, days, inJournal, last, today, 1, false), weekRate(list, days, inJournal, last, today, 7, false),
                    weekRate(list, days, inJournal, last, today, 30, false), weekRate(list, days, inJournal, last, today, 7, true),
                    weekRate(list, days, inJournal, last, today, 30, true)));
        }

        // Сводка: последние 4 зрелые недели против предыдущих 4
        Rate d1r = pool(weeks, 1, 0), d1p = pool(weeks, 1, 4), d7r = pool(weeks, 7, 0), d7p = pool(weeks, 7, 4),
                d30r = pool(weeks, 30, 0), d30p = pool(weeks, 30, 4);

        // Разрезы: учитываются игроки, у которых день N уже прошёл (по каждому показателю своя выборка)
        Map<String, List<SegmentRow>> segments = new LinkedHashMap<>();
        segments.put("Источник", segment(cohort, days, today, Member::srcType));
        segments.put("Первая игра", segment(cohort, days, today, Member::game));
        segments.put("Тип первого квеста", segment(cohort, days, today, Member::firstType));
        segments.put("Отряд (по состоянию сейчас)", segment(cohort, days, today, m -> m.inSquad() ? "в отряде" : "без отряда"));

        // Стоимость вернувшегося: расход источника (вводится вручную, за всё время) / вернувшиеся из игроков этого источника
        List<SourceCost> costs = new ArrayList<>();
        for (TrafficSource s : sources) {
            if (s.getSpendRub() <= 0) continue;
            long reg = members.stream().filter(m -> s.getCode().equals(m.srcCode())).count();
            long n7 = 0, r7 = 0, n30 = 0, r30 = 0;
            for (Member m : cohort) {
                if (!s.getCode().equals(m.srcCode())) continue;
                Set<LocalDate> act = days.getOrDefault(m.id(), Set.of());
                if (m.day0().plusDays(7).isBefore(today)) { n7++; if (act.contains(m.day0().plusDays(7))) r7++; }
                if (m.day0().plusDays(30).isBefore(today)) { n30++; if (act.contains(m.day0().plusDays(30))) r30++; }
            }
            costs.add(new SourceCost(s.getCode(), s.getName(), s.getSpendRub(), reg, n7, r7, n30, r30));
        }
        costs.sort(Comparator.comparingLong(SourceCost::spendRub).reversed());

        return new Report(today, journalStart, cohort.size(), weeks, segments, costs, d1r, d1p, d7r, d7p, d30r, d30p);
    }

    /** Недельная когорта: DN считается, только если неделя целиком внутри журнала и день N прошёл у последнего зарегистрировавшегося. */
    private static Rate weekRate(List<Member> list, Map<Long, Set<LocalDate>> days, boolean inJournal, LocalDate last, LocalDate today, int n, boolean window) {
        if (!inJournal || !last.plusDays(n).isBefore(today)) return new Rate(list.size(), 0, false);
        long ret = 0;
        for (Member m : list) {
            Set<LocalDate> act = days.getOrDefault(m.id(), Set.of());
            if (window) {
                for (int i = 1; i <= n; i++) if (act.contains(m.day0().plusDays(i))) { ret++; break; }
            } else if (act.contains(m.day0().plusDays(n))) {
                ret++;
            }
        }
        return new Rate(list.size(), ret, true);
    }

    /** Складывает зрелые недели (по убыванию даты) окном из 4: skip = сколько зрелых недель пропустить сначала. */
    private static Rate pool(List<WeekRow> weeks, int n, int skip) {
        long size = 0, ret = 0;
        int seen = 0, taken = 0;
        for (WeekRow w : weeks) {
            Rate r = n == 1 ? w.d1() : n == 7 ? w.d7() : w.d30();
            if (!r.measurable()) continue;
            if (seen++ < skip) continue;
            if (taken++ >= 4) break;
            size += r.n();
            ret += r.returned();
        }
        return taken == 0 ? new Rate(0, 0, false) : new Rate(size, ret, true);
    }

    private static List<SegmentRow> segment(List<Member> cohort, Map<Long, Set<LocalDate>> days, LocalDate today,
                                            java.util.function.Function<Member, String> key) {
        Map<String, List<Member>> groups = new LinkedHashMap<>();
        for (Member m : cohort) groups.computeIfAbsent(key.apply(m), k -> new ArrayList<>()).add(m);
        List<SegmentRow> rows = new ArrayList<>();
        for (Map.Entry<String, List<Member>> e : groups.entrySet()) {
            rows.add(new SegmentRow(e.getKey(), e.getValue().size(),
                    userRate(e.getValue(), days, today, 1), userRate(e.getValue(), days, today, 7), userRate(e.getValue(), days, today, 30)));
        }
        rows.sort(Comparator.comparingLong(SegmentRow::size).reversed());
        return rows;
    }

    /** По игрокам группы, у которых день N уже прошёл (у каждого своя зрелость). */
    private static Rate userRate(List<Member> list, Map<Long, Set<LocalDate>> days, LocalDate today, int n) {
        long eligible = 0, ret = 0;
        for (Member m : list) {
            if (!m.day0().plusDays(n).isBefore(today)) continue;
            eligible++;
            if (days.getOrDefault(m.id(), Set.of()).contains(m.day0().plusDays(n))) ret++;
        }
        return new Rate(eligible, ret, eligible > 0);
    }

    /** Тип источника игрока. Старые игроки без кода (до введения источников) - «неизвестно», а не органика, иначе органика завышена. */
    private String sourceType(AppUser u, Map<String, TrafficSource> byCode, java.time.LocalDateTime sourcesSince) {
        String code = u.getTrafficSourceCode();
        if (code != null) {
            String override = settingRepo.findById(SRCTYPE_PREFIX + code).map(AppSetting::getValue).map(String::trim).orElse(null);
            String label = typeLabel(override);
            if (label != null) return label;
            TrafficSource s = byCode.get(code);
            return s != null && s.getSpendRub() > 0 ? "платный закуп" : "метка без расхода";
        }
        if (u.getReferredByTelegramId() != null) return "реферал";
        if (sourcesSince != null && !u.getCreatedAt().isBefore(sourcesSince)) return "органика";
        return "неизвестно";
    }

    /** paid | partner | organic | referral (так задаётся командой /srctype) -> подпись. */
    public static String typeLabel(String type) {
        if (type == null) return null;
        return switch (type.toLowerCase()) {
            case "paid" -> "платный закуп";
            case "partner" -> "партнёр";
            case "organic" -> "органика";
            case "referral" -> "реферал";
            default -> null;
        };
    }

    private static String methodLabel(String method) {
        return switch (method) {
            case "game_api" -> "API игры";
            case "partner_postback" -> "постбэк партнёра";
            case "channel_check" -> "проверка подписки";
            default -> "скриншот";
        };
    }

    /** Сохраняет тип источника для кода (команда /srctype). type = paid | partner | organic | referral | auto (сброс). */
    public String setSourceType(String code, String type) {
        if (code == null || code.isBlank() || code.length() > 40) return "Укажите код источника (до 40 символов).";
        if (sourceRepo.findByCode(code).isEmpty()) return "Источник с кодом " + code + " не найден.";
        String key = SRCTYPE_PREFIX + code;
        if ("auto".equalsIgnoreCase(type)) {
            settingRepo.findById(key).ifPresent(settingRepo::delete);
            invalidate();
            return "Тип источника " + code + " сброшен: определяется автоматически (по наличию расхода).";
        }
        if (typeLabel(type) == null) return "Тип: paid, partner, organic, referral или auto.";
        AppSetting s = new AppSetting();
        s.setKey(key);
        s.setValue(type.toLowerCase());
        settingRepo.save(s);
        invalidate();
        return "Источник " + code + ": " + typeLabel(type) + ".";
    }
}
