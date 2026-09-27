package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.QuestSubmission;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;

/**
 * Отвечает на вопрос владельца (2026-09-27): «активность игроков уже на потолке ограничений (кулдауны, слоты,
 * дневные/месячные лимиты) или у неё есть запас, и ослабление лимитов реально даст больше активности?»
 * Для каждого ограничения считаем не «есть кто-то, кто уперся», а ДОЛЮ активных игроков, которые упираются
 * РЕГУЛЯРНО - это и отличает реальный потолок от разового совпадения.
 *
 * ВАЖНО про точность: это разведочная аналитика по уже накопленным данным, не биллинговый расчёт.
 * Часовой кулдаун на игру взят как единый {@code QuestService.COOLDOWN_HOURS} (24ч) для всех непервых
 * пар "игрок-игра" - в реальности он короче для UGC (12ч) и для новичков (см. QuestService.cooldownHours),
 * это упрощение сознательно не различает случаи и может немного занижать "жмёт" для UGC/новичков.
 * Если цифры используются для решения по экономике - см. project_economic_model.md (Health Ratio):
 * снятие лимита увеличивает эмиссию EXC, это отдельный расчёт, не часть этого отчёта.
 */
@Service
@RequiredArgsConstructor
public class EngagementCeilingService {

    /** Игрок считается "активным" для этого отчёта, если брал хотя бы один квест за это время. */
    private static final int ACTIVITY_WINDOW_DAYS = 30;
    /** Игрок "упирается" в кулдаун игры, если следующий квест той же игры взят не позже этого числа минут
     *  после снятия кулдауна - иначе это могло быть просто совпадение, а не реальная нехватка времени. */
    private static final long GAME_COOLDOWN_BINDING_MINUTES = 20;
    /** То же для часового лимита взятия квеста (60 мин обычно / 15 мин у новичка) - окно "почти сразу". */
    private static final long TAKE_LIMIT_BINDING_EXTRA_MINUTES = 10;
    private static final int MIN_PAIRS_FOR_COOLDOWN_STAT = 20;

    private final AppUserRepository appUserRepository;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final SinkShopService sinkShopService;

    public record LimitStat(String title, String note, Double shareBinding, long sampleSize, String extra) {}

    public record Snapshot(LocalDateTime computedAt, long activeUsers, List<LimitStat> stats) {}

    public Snapshot compute() {
        LocalDateTime since = LocalDateTime.now().minusDays(ACTIVITY_WINDOW_DAYS);
        List<QuestSubmission> submissions = questSubmissionRepository.findAllSince(since);

        Map<Long, List<QuestSubmission>> byUser = new HashMap<>();
        for (QuestSubmission s : submissions) {
            if (s.getUser() == null) continue;
            byUser.computeIfAbsent(s.getUser().getId(), k -> new ArrayList<>()).add(s);
        }
        Set<Long> activeUserIds = byUser.keySet();
        List<AppUser> activeUsers = activeUserIds.isEmpty() ? List.of() : appUserRepository.findAllById(activeUserIds);

        List<LimitStat> stats = new ArrayList<>();
        stats.add(slotStat(byUser, activeUsers));
        stats.add(gameCooldownStat(byUser));
        stats.add(takeLimitStat(byUser, activeUsers));
        stats.add(dailyCapStat("Дневной лимит реролла квеста", activeUsers,
                u -> u.getDailyRerollDate(), AppUser::getDailyRerollCount, 3));
        stats.add(dailyCapStat("Дневной лимит покупки бустов", activeUsers,
                u -> u.getDailyBoostDate(), AppUser::getDailyBoostCount, 3));
        stats.add(dailyCapStat("Дневной лимит снятия кулдауна", activeUsers,
                u -> u.getDailyCooldownDate(), AppUser::getDailyCooldownRemovals, 2));
        stats.add(withdrawalStat(activeUsers));

        return new Snapshot(LocalDateTime.now(), activeUsers.size(), stats);
    }

    // ───────────────────────── слоты квестов ─────────────────────────

    private LimitStat slotStat(Map<Long, List<QuestSubmission>> byUser, List<AppUser> activeUsers) {
        long sample = 0, binding = 0;
        for (AppUser user : activeUsers) {
            List<QuestSubmission> subs = byUser.getOrDefault(user.getId(), List.of());
            long activeSlots = subs.stream()
                    .filter(s -> (s.getStatus() == SubmissionStatus.DRAFT || s.getStatus() == SubmissionStatus.PENDING)
                            && (s.getExpiresAt() == null || s.getExpiresAt().isAfter(LocalDateTime.now())))
                    .count();
            long maxSlots = sinkShopService.getMaxQuestSlots(user);
            sample++;
            if (activeSlots >= maxSlots) binding++;
        }
        return new LimitStat("Слоты квестов", "Снимок на сейчас: доля активных игроков, у кого прямо сейчас все слоты заняты",
                sample == 0 ? null : (double) binding / sample, sample, null);
    }

    // ───────────────────────── кулдаун на игру ─────────────────────────

    private LimitStat gameCooldownStat(Map<Long, List<QuestSubmission>> byUser) {
        List<Long> gapsMinutes = new ArrayList<>();
        long binding = 0;
        for (List<QuestSubmission> subs : byUser.values()) {
            Map<String, List<QuestSubmission>> byGame = new HashMap<>();
            for (QuestSubmission s : subs) {
                if (s.getQuest() == null || s.getQuest().getGameName() == null) continue;
                byGame.computeIfAbsent(s.getQuest().getGameName(), k -> new ArrayList<>()).add(s);
            }
            for (List<QuestSubmission> gameSubs : byGame.values()) {
                gameSubs.sort(Comparator.comparing(QuestSubmission::getCreatedAt));
                for (int i = 0; i < gameSubs.size() - 1; i++) {
                    QuestSubmission prev = gameSubs.get(i);
                    if (prev.getStatus() != SubmissionStatus.APPROVED || prev.getUpdatedAt() == null) continue;
                    QuestSubmission next = gameSubs.get(i + 1);
                    LocalDateTime cooldownEnds = prev.getUpdatedAt().plusHours(24);
                    long gap = ChronoUnit.MINUTES.between(cooldownEnds, next.getCreatedAt());
                    if (gap < 0) continue; // не должно случаться при рабочем кулдауне, пропускаем как аномалию
                    gapsMinutes.add(gap);
                    if (gap <= GAME_COOLDOWN_BINDING_MINUTES) binding++;
                }
            }
        }
        Double share = gapsMinutes.isEmpty() ? null : (double) binding / gapsMinutes.size();
        String extra = gapsMinutes.isEmpty() ? null : "медиана паузы после снятия кулдауна: " + median(gapsMinutes) + " мин.";
        return new LimitStat("Кулдаун на игру (24 ч)",
                "Доля повторных заходов в ту же игру, случившихся не позже " + GAME_COOLDOWN_BINDING_MINUTES + " мин. после снятия кулдауна "
                        + "(упрощение: единый расчёт 24ч для всех, без учёта укороченного кулдауна UGC/новичков)",
                gapsMinutes.size() < MIN_PAIRS_FOR_COOLDOWN_STAT ? null : share, gapsMinutes.size(), extra);
    }

    // ───────────────────────── часовой лимит взятия ─────────────────────────

    private LimitStat takeLimitStat(Map<Long, List<QuestSubmission>> byUser, List<AppUser> activeUsers) {
        Map<Long, AppUser> usersById = new HashMap<>();
        for (AppUser u : activeUsers) usersById.put(u.getId(), u);

        List<Long> gapsMinutes = new ArrayList<>();
        long binding = 0;
        for (Map.Entry<Long, List<QuestSubmission>> e : byUser.entrySet()) {
            AppUser user = usersById.get(e.getKey());
            if (user == null) continue;
            long limitMinutes = user.getCompletedQuests() < QuestService.ONBOARDING_QUEST_THRESHOLD ? 15 : 60;
            List<QuestSubmission> subs = new ArrayList<>(e.getValue());
            subs.sort(Comparator.comparing(QuestSubmission::getCreatedAt));
            for (int i = 0; i < subs.size() - 1; i++) {
                long gap = ChronoUnit.MINUTES.between(subs.get(i).getCreatedAt(), subs.get(i + 1).getCreatedAt());
                if (gap < 0) continue;
                gapsMinutes.add(gap);
                if (gap >= limitMinutes && gap <= limitMinutes + TAKE_LIMIT_BINDING_EXTRA_MINUTES) binding++;
            }
        }
        Double share = gapsMinutes.isEmpty() ? null : (double) binding / gapsMinutes.size();
        return new LimitStat("Часовой лимит взятия квеста",
                "Доля пар «взял квест -> взял следующий», где пауза попала в первые " + TAKE_LIMIT_BINDING_EXTRA_MINUTES
                        + " мин. после открытия лимита (60 мин обычно, 15 мин у новичка)",
                gapsMinutes.size() < MIN_PAIRS_FOR_COOLDOWN_STAT ? null : share, gapsMinutes.size(), null);
    }

    // ───────────────────────── дневные лимиты ─────────────────────────

    private interface DateGetter { LocalDate get(AppUser u); }

    private LimitStat dailyCapStat(String title, List<AppUser> activeUsers, DateGetter dateGetter,
                                    java.util.function.ToIntFunction<AppUser> countGetter, int max) {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        long usedYesterday = 0, hitCap = 0;
        for (AppUser user : activeUsers) {
            LocalDate d = dateGetter.get(user);
            if (d == null || !d.equals(yesterday)) continue;
            usedYesterday++;
            if (countGetter.applyAsInt(user) >= max) hitCap++;
        }
        return new LimitStat(title, "Среди тех, кто пользовался этим вчера: доля, кто вчера же исчерпал дневной лимит (" + max + "/сутки)",
                usedYesterday == 0 ? null : (double) hitCap / usedYesterday, usedYesterday, null);
    }

    // ───────────────────────── месячный лимит вывода ─────────────────────────

    private LimitStat withdrawalStat(List<AppUser> activeUsers) {
        long withdrawers = 0, atCeiling = 0;
        for (AppUser user : activeUsers) {
            if (user.getMonthlyWithdrawnExc() <= 0) continue;
            withdrawers++;
            if (sinkShopService.getRemainingWithdrawalLimit(user) == 0) atCeiling++;
        }
        return new LimitStat("Месячный лимит вывода", "Среди тех, кто выводил в этом месяце: доля, кто уже упёрся в свой месячный потолок",
                withdrawers == 0 ? null : (double) atCeiling / withdrawers, withdrawers, null);
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    // ───────────────────────── форматирование ─────────────────────────

    public String format(Snapshot snap) {
        StringBuilder sb = new StringBuilder("🚧 <b>Потолок ограничений</b>\n\n");
        sb.append("За последние ").append(ACTIVITY_WINDOW_DAYS).append(" дн. активных игроков (брали квест хотя бы раз): <b>")
                .append(snap.activeUsers()).append("</b>.\n\n");
        sb.append("Ниже по каждому ограничению - доля тех, кто упирается в него РЕГУЛЯРНО, а не просто иногда. ")
                .append("Высокая доля = ограничение реально сдерживает активность, низкая = запас ещё есть.\n\n");
        for (LimitStat s : snap.stats()) {
            sb.append("<b>").append(esc(s.title())).append("</b>\n");
            if (s.shareBinding() == null) {
                sb.append("Недостаточно данных за период (выборка: ").append(s.sampleSize()).append(").\n");
            } else {
                sb.append(pct(s.shareBinding())).append(" упираются регулярно (выборка: ").append(s.sampleSize()).append(")\n");
                sb.append(esc(s.note())).append("\n");
                if (s.extra() != null) sb.append(esc(s.extra())).append("\n");
            }
            sb.append("\n");
        }
        sb.append("<i>Разведочная оценка по накопленным данным, не биллинговый расчёт (см. упрощения в подсказках выше). ")
                .append("Снятие любого лимита увеличивает эмиссию EXC - перед включением сверить с Health Ratio.</i>");
        return sb.toString();
    }

    private static String pct(double v) {
        return String.format(Locale.forLanguageTag("ru"), "%.0f%%", v * 100);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
