package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.NotificationLog;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.NotificationLogRepository;

/**
 * Шлюз частоты напоминаний (решение владельца 2026-09-24: «никакого спама, редко внутри дня»). Каждая рассылка-напоминание
 * спрашивает {@link #tryAcquire} ДО отправки и ДО отметки своего состояния (иначе отклонённое напоминание «сгорело» бы):
 *  - в скользящие {@value #WINDOW_HOURS} ч после последнего напоминания идёт только более важное (см. NudgeType.priority);
 *  - не больше {@value #MAX_PER_24H} напоминаний за 24 ч;
 *  - дедлайн квеста (bypassLimit) идёт всегда, но пишется в журнал и учитывается для остальных.
 * Отклонённое напоминание не копится: одноразовые повторяются на следующем тике планировщика, окно-запросы просто пропускаются.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationGateService {

    public static final int WINDOW_HOURS = 20;
    public static final int MAX_PER_24H = 2;
    /** «Вернулся» - активность игрока в течение этого срока после напоминания. */
    public static final int RETURN_WINDOW_HOURS = 48;

    private final AppUserRepository appUserRepository;
    private final NotificationLogRepository logRepository;

    /** Сколько раз лимит отклонил напоминание данного типа с запуска бота (для отчёта; не хранится в БД). */
    private final Map<NudgeType, AtomicLong> blocked = new ConcurrentHashMap<>();

    @Transactional
    public boolean tryAcquire(AppUser user, NudgeType type) {
        LocalDateTime now = LocalDateTime.now();
        if (!type.isBypassLimit()) {
            LocalDateTime last = user.getLastNudgeAt();
            if (last != null && last.isAfter(now.minusHours(WINDOW_HOURS)) && user.getLastNudgePriority() >= type.getPriority()) {
                blocked.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
                return false;
            }
            if (logRepository.countByUserIdAndSentAtAfter(user.getId(), now.minusHours(24)) >= MAX_PER_24H) {
                blocked.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
                return false;
            }
        }
        NotificationLog entry = new NotificationLog();
        entry.setUserId(user.getId());
        entry.setType(type.name());
        entry.setPriority(type.getPriority());
        entry.setSentAt(now);
        logRepository.save(entry);
        // Точечный UPDATE, а не save(user): вызывающие планировщики держат «старый» объект игрока, и полная запись затёрла бы чужие поля
        appUserRepository.markNudge(user.getId(), now, type.getPriority());
        user.setLastNudgeAt(now);
        user.setLastNudgePriority(type.getPriority());
        user.setLastNudgeReturned(false);
        return true;
    }

    /** Вызывается при любой активности игрока (бот/мини-апп): если после последнего напоминания прошло меньше 48 ч, а «вернулся»
     * ещё не отмечен - отмечает в журнале. Дешёвая проверка по уже загруженному игроку, запрос в БД только при попадании. */
    @Transactional
    public void markReturnIfNeeded(AppUser user) {
        LocalDateTime last = user.getLastNudgeAt();
        if (last == null || user.isLastNudgeReturned()) return;
        LocalDateTime now = LocalDateTime.now();
        if (last.isBefore(now.minusHours(RETURN_WINDOW_HOURS))) return;
        logRepository.findFirstByUserIdOrderBySentAtDesc(user.getId()).ifPresent(entry -> {
            if (entry.getReturnedAt() == null) {
                entry.setReturnedAt(now);
                logRepository.save(entry);
            }
        });
        appUserRepository.markNudgeReturned(user.getId());
        user.setLastNudgeReturned(true);
    }

    public record ReportRow(String label, long sent, long mature, long returned) {}

    /** Отчёт по типам напоминаний за days дней: отправлено, из них «созревших» (старше 48 ч) и сколько из них вернулись. */
    public java.util.List<ReportRow> report(int days) {
        LocalDateTime now = LocalDateTime.now();
        java.util.List<ReportRow> rows = new java.util.ArrayList<>();
        for (Object[] r : logRepository.reportByType(now.minusDays(days), now.minusHours(RETURN_WINDOW_HOURS))) {
            String type = (String) r[0];
            String label;
            try {
                label = NudgeType.valueOf(type).getLabel();
            } catch (IllegalArgumentException e) {
                label = type;
            }
            rows.add(new ReportRow(label, ((Number) r[1]).longValue(),
                    r[2] == null ? 0 : ((Number) r[2]).longValue(), r[3] == null ? 0 : ((Number) r[3]).longValue()));
        }
        rows.sort(java.util.Comparator.comparingLong(ReportRow::sent).reversed());
        return rows;
    }

    public Map<NudgeType, Long> blockedSinceStart() {
        Map<NudgeType, Long> result = new java.util.EnumMap<>(NudgeType.class);
        blocked.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }
}
