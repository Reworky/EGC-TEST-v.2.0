package ru.gamebot.platform.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.BotHeartbeat;
import ru.gamebot.platform.domain.repository.BotHeartbeatRepository;

/** Аптайм бота по отметкам «жив» раз в 5 минут (вкладка «Техническое здоровье»). Отметок пока нет - метрика показывает «—»,
 *  накапливаться начнёт с первого запуска после деплоя (бэклога нет, планировщик состояния не читает). */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeartbeatService {

    static final long INTERVAL_MIN = 5;
    private static final int KEEP_DAYS = 120;

    private final BotHeartbeatRepository repository;

    @Scheduled(fixedRate = 300_000, initialDelay = 30_000)
    public void beat() {
        try {
            BotHeartbeat h = new BotHeartbeat();
            h.setTs(LocalDateTime.now());
            repository.save(h);
            repository.deleteOlderThan(LocalDateTime.now().minusDays(KEEP_DAYS));
        } catch (Exception e) {
            log.warn("Heartbeat failed", e);
        }
    }

    /** Доля времени онлайн в окне, %; null - отметок за окно ещё не было (окно начинается раньше первой отметки - считаем с неё). */
    public Double uptimePercent(LocalDateTime from, LocalDateTime to) {
        Optional<BotHeartbeat> first = repository.findFirstByOrderByTsAsc();
        if (first.isEmpty()) return null;
        LocalDateTime start = first.get().getTs().isAfter(from) ? first.get().getTs() : from;
        long minutes = Duration.between(start, to).toMinutes();
        if (minutes < INTERVAL_MIN) return null;
        long expected = minutes / INTERVAL_MIN;
        long beats = repository.countByTsGreaterThanEqualAndTsLessThan(start, to);
        return Math.min(100.0, beats * 100.0 / expected);
    }
}
