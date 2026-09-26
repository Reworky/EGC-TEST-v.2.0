package ru.gamebot.platform.service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Копит в памяти ошибки процесса для админской кнопки «🩺 Проверка ошибок» (2026-09-25): WARN/ERROR из логов
 * (см. ErrorMonitorInstaller) и HTTP-ответы 4xx/5xx мини-аппа (см. HttpStatsFilter). Данные живут только до
 * перезапуска бота - после деплоя счётчики начинаются заново (так и задумано: кнопка отвечает «есть ли проблемы у
 * ТЕКУЩЕЙ версии»). События старше 48 ч и сверх лимита вытесняются. Никаких внешних зависимостей, ничего не пишет в БД.
 */
@Service
public class ErrorMonitorService {

    public enum Status { OK, WARN, ERROR }

    public record Group(String level, String key, long count, long lastTs) {}
    public record HttpGroup(int status, String route, long count) {}

    public record Summary(long startedAt, long since, Status status,
                          long errors, long warns, long benign,
                          List<Group> topLogs,
                          long http5xx, long http4xx, long http404,
                          List<HttpGroup> topHttp, List<HttpGroup> top404) {}

    private record LogEvent(long ts, String level, String key, boolean benign) {}
    private record HttpEvent(long ts, int status, String route) {}

    private static final int MAX_EVENTS = 5000;
    private static final long RETENTION_MS = 48L * 3600_000;
    /** WARN в первые минуты после старта - это диагностика инициализации (миграции, отключённые интеграции), не поломка. */
    private static final long STARTUP_GRACE_MS = 120_000;

    /** Известный безвредный шум: игрок заблокировал бота, устаревшая кнопка, мусорные запросы сканеров на HTTP-порт. */
    private static final List<String> BENIGN_MARKERS = List.of(
            "bot was blocked by the user",
            "query is too old",
            "Failed to answer callback",
            "Invalid character found in method name",
            "user is deactivated",
            "chat not found",
            "Forbidden: bot can't initiate conversation");

    private final long startedAt = System.currentTimeMillis();
    private volatile long baselineTs = startedAt;
    private final Deque<LogEvent> logEvents = new ArrayDeque<>();
    private final Deque<HttpEvent> httpEvents = new ArrayDeque<>();

    public long getStartedAt() {
        return startedAt;
    }

    public void recordLog(String level, String logger, String message, String exceptionClass, String exceptionMessage, String causes) {
        long now = System.currentTimeMillis();
        boolean isWarn = "WARN".equals(level);
        if (isWarn && now - startedAt < STARTUP_GRACE_MS) {
            return;
        }
        String full = (message == null ? "" : message) + " " + (exceptionMessage == null ? "" : exceptionMessage) + " " + (causes == null ? "" : causes);
        boolean benign = BENIGN_MARKERS.stream().anyMatch(full::contains);
        String shortLogger = logger == null ? "?" : logger.substring(logger.lastIndexOf('.') + 1);
        StringBuilder key = new StringBuilder(shortLogger).append(": ").append(normalize(message, 110));
        if (exceptionClass != null) {
            key.append(" | ").append(exceptionClass.substring(exceptionClass.lastIndexOf('.') + 1));
            if (exceptionMessage != null && !exceptionMessage.isBlank()) {
                key.append(": ").append(normalize(exceptionMessage, 80));
            }
        }
        synchronized (logEvents) {
            logEvents.addLast(new LogEvent(now, level, key.toString(), benign));
            trim(logEvents, now);
        }
    }

    public void recordHttp(int status, String route) {
        long now = System.currentTimeMillis();
        synchronized (httpEvents) {
            httpEvents.addLast(new HttpEvent(now, status, route));
            trim(httpEvents, now);
        }
    }

    /** «Сбросить счётчики»: всё, что было до этого момента, в сводку больше не попадает (например, после разбора причин). */
    public void resetCounters() {
        baselineTs = System.currentTimeMillis();
    }

    public Summary summarize(int hours) {
        long now = System.currentTimeMillis();
        long from = Math.max(baselineTs, now - hours * 3600_000L);

        long errors = 0, warns = 0, benign = 0;
        Map<String, long[]> groups = new HashMap<>(); // key -> [count, lastTs]
        Map<String, String> groupLevel = new HashMap<>();
        synchronized (logEvents) {
            for (LogEvent e : logEvents) {
                if (e.ts() < from) continue;
                if (e.benign()) {
                    benign++;
                    continue;
                }
                if ("ERROR".equals(e.level())) errors++; else warns++;
                long[] g = groups.computeIfAbsent(e.key(), k -> new long[2]);
                g[0]++;
                g[1] = Math.max(g[1], e.ts());
                // ERROR перекрывает WARN, если ключ встречался с обоими уровнями
                groupLevel.merge(e.key(), e.level(), (a, b) -> "ERROR".equals(a) || "ERROR".equals(b) ? "ERROR" : "WARN");
            }
        }
        List<Group> topLogs = new ArrayList<>();
        groups.forEach((k, g) -> topLogs.add(new Group(groupLevel.get(k), k, g[0], g[1])));
        topLogs.sort(Comparator.comparing((Group g) -> !"ERROR".equals(g.level())).thenComparing(Comparator.comparingLong(Group::count).reversed()));

        long http5xx = 0, http4xx = 0, http404 = 0;
        Map<String, Long> httpCounts = new HashMap<>();
        Map<String, Long> notFoundCounts = new HashMap<>();
        synchronized (httpEvents) {
            for (HttpEvent e : httpEvents) {
                if (e.ts() < from) continue;
                if (e.status() >= 500) {
                    http5xx++;
                    httpCounts.merge(e.status() + " " + e.route(), 1L, Long::sum);
                } else if (e.status() == 404) {
                    http404++;
                    notFoundCounts.merge("404 " + e.route(), 1L, Long::sum);
                } else if (e.status() != 401) {
                    http4xx++;
                    httpCounts.merge(e.status() + " " + e.route(), 1L, Long::sum);
                }
            }
        }
        Status status = (errors > 0 || http5xx > 0) ? Status.ERROR : (warns > 0 || http4xx > 0) ? Status.WARN : Status.OK;
        return new Summary(startedAt, from, status, errors, warns, benign,
                topLogs.subList(0, Math.min(8, topLogs.size())),
                http5xx, http4xx, http404, topHttp(httpCounts, 6), topHttp(notFoundCounts, 3));
    }

    private static List<HttpGroup> topHttp(Map<String, Long> counts, int limit) {
        List<HttpGroup> list = new ArrayList<>();
        counts.forEach((k, c) -> {
            int sp = k.indexOf(' ');
            list.add(new HttpGroup(Integer.parseInt(k.substring(0, sp)), k.substring(sp + 1), c));
        });
        list.sort(Comparator.comparingLong(HttpGroup::count).reversed());
        return list.subList(0, Math.min(limit, list.size()));
    }

    private static String normalize(String s, int max) {
        if (s == null) return "";
        String n = s.replaceAll("[0-9]{6,}", "N").replaceAll("\\s+", " ").trim();
        return n.length() > max ? n.substring(0, max) + "…" : n;
    }

    private static <T> void trim(Deque<T> deque, long now) {
        while (deque.size() > MAX_EVENTS) {
            deque.removeFirst();
        }
        while (!deque.isEmpty()) {
            long ts = deque.peekFirst() instanceof LogEvent l ? l.ts() : ((HttpEvent) deque.peekFirst()).ts();
            if (now - ts > RETENTION_MS) deque.removeFirst(); else break;
        }
    }

    public static Instant toInstant(long ts) {
        return Instant.ofEpochMilli(ts);
    }
}
