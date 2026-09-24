package ru.gamebot.platform.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.event.DeployFinishedEvent;

/**
 * Мост между кнопкой «🚀 Обновить бота» и агентом выкладки на сервере (scripts/deploy-agent.sh). Бот НЕ имеет доступа к
 * Docker и сам ничего не собирает: он только кладёт файл-заявку в общую папку {@code /data/deploy} и читает файлы,
 * которые пишет агент (status, pending.txt, deployed_commit, heartbeat, last.log). Агент принимает лишь слова
 * check / deploy / rollback, поэтому даже скомпрометированный бот не может запустить произвольную команду на сервере.
 */
@Slf4j
@Service
public class DeployService {

    public record Status(String state, String action, long requestedBy, long startedAt, long finishedAt,
                         String commit, String subject, String prevCommit, String message) {}

    /** Агент считается живым, если проверка коммитов (таймер раз в 2 мин) отмечалась за последние 10 минут. */
    private static final long HEARTBEAT_MAX_AGE_SEC = 600;

    private final Path dir = Path.of(System.getenv().getOrDefault("DEPLOY_DIR", "/data/deploy"));
    private final ApplicationEventPublisher publisher;

    public DeployService(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public boolean agentAlive() {
        String hb = read("heartbeat");
        if (hb == null) return false;
        try {
            return System.currentTimeMillis() / 1000 - Long.parseLong(hb.trim()) <= HEARTBEAT_MAX_AGE_SEC;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public Optional<Status> readStatus() {
        String raw = read("status");
        if (raw == null || raw.isBlank()) return Optional.empty();
        Map<String, String> kv = new HashMap<>();
        for (String line : raw.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) kv.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return Optional.of(new Status(
                kv.getOrDefault("state", "idle"), kv.getOrDefault("action", ""),
                num(kv.get("requested_by")), num(kv.get("started_at")), num(kv.get("finished_at")),
                kv.getOrDefault("commit", ""), kv.getOrDefault("subject", ""),
                kv.getOrDefault("prev_commit", ""), kv.getOrDefault("message", "")));
    }

    /** «хеш тема» коммита, который сейчас работает, или null, если агент ещё не записал. */
    public String deployedCommit() {
        String s = read("deployed_commit");
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Коммиты, которые ещё не выложены (по одному в строке: «хеш тема»). */
    public List<String> pending() {
        List<String> result = new ArrayList<>();
        String raw = read("pending.txt");
        if (raw != null) {
            for (String line : raw.split("\n")) {
                if (!line.isBlank()) result.add(line.trim());
            }
        }
        return result;
    }

    public String logTail(int maxChars) {
        String raw = read("last.log");
        if (raw == null || raw.isBlank()) return "";
        return raw.length() > maxChars ? "…" + raw.substring(raw.length() - maxChars) : raw;
    }

    /** Кладёт заявку агенту. action - строго check / deploy / rollback. Возвращает false, если папки агента нет. */
    public boolean request(String action, long telegramId) {
        if (!List.of("check", "deploy", "rollback").contains(action)) return false;
        try {
            if (!Files.isDirectory(dir)) return false;
            Files.writeString(dir.resolve("request"), action + " " + telegramId + "\n", StandardCharsets.UTF_8);
            log.info("[Deploy] requested action={} by={}", action, telegramId);
            return true;
        } catch (IOException e) {
            log.warn("[Deploy] failed to write request", e);
            return false;
        }
    }

    /**
     * Сообщает инициатору итог выкладки/отката. После успешной выкладки бот перезапускается, поэтому «уже сообщали» хранится
     * в файле notified (время завершения последнего сообщённого запуска), а не в памяти. Первый тик через 20 секунд после
     * старта - чтобы Telegram-бот успел зарегистрироваться до отправки.
     */
    @Scheduled(initialDelay = 20_000, fixedDelay = 5_000)
    public void announceResult() {
        try {
            Optional<Status> opt = readStatus();
            if (opt.isEmpty()) return;
            Status s = opt.get();
            boolean finished = List.of("ok", "failed", "rolled_back").contains(s.state());
            boolean ours = "deploy".equals(s.action()) || "rollback".equals(s.action());
            if (!finished || !ours || s.requestedBy() <= 0 || s.finishedAt() <= 0) return;
            long notified = num(read("notified"));
            if (s.finishedAt() <= notified) return;
            Files.writeString(dir.resolve("notified"), String.valueOf(s.finishedAt()), StandardCharsets.UTF_8);
            publisher.publishEvent(new DeployFinishedEvent(this, s));
        } catch (Exception e) {
            log.warn("[Deploy] announceResult failed", e);
        }
    }

    private String read(String name) {
        try {
            Path p = dir.resolve(name);
            return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static long num(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
