package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;
import ru.gamebot.platform.domain.enums.SubmissionStatus;
import ru.gamebot.platform.domain.model.PlatformSnapshot;
import ru.gamebot.platform.domain.model.Quest;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.PlatformSnapshotRepository;
import ru.gamebot.platform.domain.repository.QuestRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.event.QuestPoolStaleEvent;

/** Следит, не отстаёт ли пул квестов от базы игроков. Главный сигнал — чистый рост числа АКТИВНЫХ квестов за
 *  14 дней по ежедневным снапшотам платформы (а не число «созданных»: сидер пересоздаёт часть квестов на каждом
 *  старте, из-за этого createdAt врёт). Раз в неделю шлёт админам алерт, только если рост нулевой или отрицательный;
 *  тот же отчёт доступен по кнопке в админ-статистике. */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestPoolHealthService {

    private static final int GROWTH_WINDOW_DAYS = 14;
    private static final int TOP_GAMES = 5;
    private static final int DEAD_GAMES_SHOWN = 8;
    private static final int DEAD_QUESTS_SHOWN = 12;
    /** Квесту моложе стольких дней рано выносить приговор «мёртвый». */
    private static final int FRESH_QUEST_DAYS = 30;

    private final QuestRepository questRepository;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final PlatformSnapshotRepository snapshotRepository;
    private final AppUserRepository appUserRepository;
    private final ApplicationEventPublisher eventPublisher;

    public record Report(long activeQuests, Long activeQuestsBefore, Long daysBefore, long players7d,
                         long approvals7d, long approvalsPrev7d, long deadQuests, int deadPercent,
                         List<String> topGames, List<String> deadGames,
                         long freshDeadQuests, List<String> neverTakenQuests, List<String> takenNotDoneQuests) {

        /** Чистый рост активных квестов за окно; null — снапшотов за нужный срок ещё нет. */
        public Long netGrowth() {
            return activeQuestsBefore == null ? null : activeQuests - activeQuestsBefore;
        }

        public boolean stale() {
            Long g = netGrowth();
            return g != null && g <= 0;
        }
    }

    @Transactional(readOnly = true)
    public Report build() {
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();

        List<Quest> active = questRepository.findAllByActiveTrueOrderByCreatedAtDesc();
        long activeCount = active.size();

        Long before = null;
        Long daysBefore = null;
        List<PlatformSnapshot> snaps = snapshotRepository.findTop30ByOrderBySnapshotDateDesc(); // новые → старые
        PlatformSnapshot base = null;
        for (PlatformSnapshot s : snaps) {
            if (!s.getSnapshotDate().isAfter(today.minusDays(GROWTH_WINDOW_DAYS))) { base = s; break; }
        }
        if (base == null && !snaps.isEmpty()) {
            PlatformSnapshot oldest = snaps.get(snaps.size() - 1);
            if (ChronoUnit.DAYS.between(oldest.getSnapshotDate(), today) >= 7) base = oldest;
        }
        if (base != null) {
            before = base.getActiveQuestsCount();
            daysBefore = ChronoUnit.DAYS.between(base.getSnapshotDate(), today);
        }

        long players7d = appUserRepository.countActiveSince(today.minusDays(7));
        long approvals7d = questSubmissionRepository.countApprovedSince(now.minusDays(7));
        long approvals14d = questSubmissionRepository.countApprovedSince(now.minusDays(14));

        Map<Long, Long> perQuest = new HashMap<>();
        for (Object[] r : questSubmissionRepository.countApprovedGroupedByQuestSince(now.minusDays(30))) {
            perQuest.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
        }

        Map<Long, Map<SubmissionStatus, Long>> takenByStatus = new HashMap<>();
        for (Object[] r : questSubmissionRepository.countTakenByStatusGroupedByQuestSince(now.minusDays(30))) {
            takenByStatus.computeIfAbsent(((Number) r[0]).longValue(), k -> new EnumMap<>(SubmissionStatus.class))
                    .put((SubmissionStatus) r[1], ((Number) r[2]).longValue());
        }

        long dead = 0;
        long freshDead = 0;
        List<String> neverTaken = new ArrayList<>();
        List<String> takenNotDone = new ArrayList<>();
        Map<String, long[]> byGame = new TreeMap<>(); // игра -> {квестов, выполнений за 30д}
        for (Quest q : active) {
            long done = perQuest.getOrDefault(q.getId(), 0L);
            if (done == 0) {
                dead++;
                boolean fresh = q.getCreatedAt() != null && q.getCreatedAt().isAfter(now.minusDays(FRESH_QUEST_DAYS));
                Map<SubmissionStatus, Long> byStatus = takenByStatus.getOrDefault(q.getId(), Map.of());
                long takes = byStatus.values().stream().mapToLong(Long::longValue).sum();
                String label = deadLabel(q);
                if (fresh) freshDead++;
                else if (takes == 0) neverTaken.add(label);
                else takenNotDone.add(label + " (брали: " + takes + statusBreakdown(byStatus) + ")");
            }
            String game = q.getGameName() == null || q.getGameName().isBlank() ? "Без игры" : q.getGameName();
            long[] g = byGame.computeIfAbsent(game, k -> new long[2]);
            g[0]++;
            g[1] += done;
        }

        List<Map.Entry<String, long[]>> games = new ArrayList<>(byGame.entrySet());
        games.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
        List<String> top = new ArrayList<>();
        for (Map.Entry<String, long[]> e : games) {
            if (top.size() >= TOP_GAMES || e.getValue()[1] == 0) break;
            top.add(HtmlUtils.htmlEscape(e.getKey()) + " — " + e.getValue()[1] + " (квестов: " + e.getValue()[0] + ")");
        }
        List<String> deadGames = new ArrayList<>();
        for (Map.Entry<String, long[]> e : games) {
            if (e.getValue()[1] == 0 && deadGames.size() < DEAD_GAMES_SHOWN) {
                deadGames.add(HtmlUtils.htmlEscape(e.getKey()) + " (" + e.getValue()[0] + ")");
            }
        }

        int deadPercent = activeCount == 0 ? 0 : (int) Math.round(dead * 100.0 / activeCount);
        return new Report(activeCount, before, daysBefore, players7d, approvals7d, approvals14d - approvals7d,
                dead, deadPercent, top, deadGames, freshDead, neverTaken, takenNotDone);
    }

    /** «; на модерации: 3; отклонено: 1» - только ненулевые статусы, чтобы очередь модерации было видно сразу. */
    private static String statusBreakdown(Map<SubmissionStatus, Long> byStatus) {
        StringBuilder sb = new StringBuilder();
        appendStatus(sb, byStatus, SubmissionStatus.PENDING, "на модерации");
        appendStatus(sb, byStatus, SubmissionStatus.NEEDS_INFO, "нужны уточнения");
        appendStatus(sb, byStatus, SubmissionStatus.REJECTED, "отклонено");
        appendStatus(sb, byStatus, SubmissionStatus.DRAFT, "в работе");
        appendStatus(sb, byStatus, SubmissionStatus.CANCELLED, "отменено");
        return sb.toString();
    }

    private static void appendStatus(StringBuilder sb, Map<SubmissionStatus, Long> byStatus, SubmissionStatus status, String label) {
        long n = byStatus.getOrDefault(status, 0L);
        if (n > 0) sb.append("; ").append(label).append(": ").append(n);
    }

    private static String deadLabel(Quest q) {
        String game = q.getGameName() == null || q.getGameName().isBlank() ? "Без игры" : q.getGameName();
        String title = q.getTitle() == null ? "" : q.getTitle();
        if (title.length() > 55) title = title.substring(0, 52) + "...";
        return HtmlUtils.htmlEscape(game) + ": " + HtmlUtils.htmlEscape(title);
    }

    private static void appendList(StringBuilder sb, String header, List<String> items) {
        if (items.isEmpty()) return;
        sb.append("\n").append(header).append("\n");
        int shown = 0;
        for (String item : items) {
            if (shown++ >= DEAD_QUESTS_SHOWN) break;
            sb.append("• ").append(item).append("\n");
        }
        if (items.size() > DEAD_QUESTS_SHOWN) sb.append("… и ещё ").append(items.size() - DEAD_QUESTS_SHOWN).append("\n");
    }

    public String format(Report r, boolean asAlert) {
        StringBuilder sb = new StringBuilder();
        sb.append(asAlert ? "⚠️ <b>Пул квестов не растёт</b>" : "🧭 <b>Пул квестов</b>").append("\n\n");

        sb.append("Активных квестов: <b>").append(r.activeQuests()).append("</b>");
        if (r.netGrowth() != null) {
            long g = r.netGrowth();
            sb.append(" (").append(r.daysBefore()).append(" дн. назад: ").append(r.activeQuestsBefore())
              .append(", <b>").append(g > 0 ? "+" : "").append(g).append("</b>)");
        } else {
            sb.append(" (снапшотов за 14 дней ещё нет — динамика появится позже)");
        }
        sb.append("\n");
        sb.append("Активных игроков за 7 дн.: <b>").append(r.players7d()).append("</b>");
        if (r.players7d() > 0) {
            sb.append(" → квестов на игрока: <b>").append(String.format("%.2f", (double) r.activeQuests() / r.players7d())).append("</b>");
        }
        sb.append("\n");
        sb.append("Выполнений за 7 дн.: <b>").append(r.approvals7d()).append("</b> (неделей ранее: ").append(r.approvalsPrev7d()).append(")\n");
        sb.append("Квестов без единого выполнения за 30 дн.: <b>").append(r.deadQuests()).append("</b> из ")
          .append(r.activeQuests()).append(" (").append(r.deadPercent()).append("%)\n");

        if (!r.topGames().isEmpty()) {
            sb.append("\n🔥 <b>Где сейчас спрос</b> (выполнения за 30 дн.):\n");
            int i = 1;
            for (String line : r.topGames()) sb.append(i++).append(". ").append(line).append("\n");
        }
        if (!r.deadGames().isEmpty()) {
            sb.append("\n🪫 <b>Квесты есть, выполнений за 30 дн. нет:</b> ").append(String.join(", ", r.deadGames())).append("\n");
        }
        appendList(sb, "🛠 <b>Берут, но не выполняют</b> (сломана проверка или слишком сложно, смотреть первыми):", r.takenNotDoneQuests());
        appendList(sb, "💤 <b>Никто не берёт</b> за 30 дн. (кандидаты на замену):", r.neverTakenQuests());
        if (r.freshDeadQuests() > 0) {
            sb.append("\n🆕 Ещё ").append(r.freshDeadQuests()).append(" квест(ов) моложе ").append(FRESH_QUEST_DAYS)
              .append(" дн. без выполнений - пока рано судить.\n");
        }
        if (asAlert) {
            sb.append("\nЗа ").append(r.daysBefore()).append(" дн. новых активных квестов не прибавилось, а игроки продолжают приходить. ")
              .append("Игрокам, выполнившим всё доступное, брать нечего — время добавлять квесты (в первую очередь в играх из блока «где спрос»).");
        }
        return sb.toString();
    }

    /** Понедельник 09:30 UTC. Состояния не хранит:
     *  после деплоя ничего не отправит до ближайшего понедельника, накопленный бэклог не влияет. */
    @Scheduled(cron = "0 30 9 * * MON")
    public void weeklyCheck() {
        try {
            Report r = build();
            if (r.stale()) {
                eventPublisher.publishEvent(new QuestPoolStaleEvent(this, format(r, true)));
            }
        } catch (Exception e) {
            log.error("Quest pool health check failed", e);
        }
    }
}
