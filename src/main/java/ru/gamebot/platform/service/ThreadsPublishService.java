package ru.gamebot.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.model.ThreadsPostLog;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.ThreadsPostLogRepository;
import ru.gamebot.platform.event.ThreadsAlertEvent;

/**
 * Автопубликация месячного контент-плана в Threads (решение владельца 23.11.2026: всё на автомате, без согласования каждого поста).
 *
 * Посты лежат в ресурсе threads_posts.json: key, at (UTC), rubric, text, необязательные reply (ответ-ссылка под своим постом,
 * публикуется со следующего тика, то есть через ~5 минут) и image (публичный URL картинки). Токены {{LINK}}, {{CHANNEL}}, {{PAYOUTS}} в text/reply
 * заменяются ссылками. Без картинок бот ничего не генерирует и не стоит кредитов.
 *
 * Безопасность по бэклогу: выключено по умолчанию (THREADS_ENABLED или app_settings threads.enabled); за один тик уходит не больше одного поста;
 * между постами не меньше 3 часов; пост, опоздавший больше чем на 12 часов, пропускается (SKIPPED), а не выходит пачкой. Запись в журнал
 * (PENDING) создаётся до обращения к API, поэтому при сбое пост не задваивается: зависший PENDING помечается FAILED и админам приходит алерт.
 * Токен Threads живёт 60 дней и продлевается сам (th_refresh_token); при сбоях админы предупреждаются.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThreadsPublishService {

    private static final String RESOURCE = "/threads_posts.json";
    private static final String API = "https://graph.threads.net/v1.0";
    private static final Duration MAX_OVERDUE = Duration.ofHours(12);
    private static final Duration MIN_GAP = Duration.ofHours(3);
    private static final Duration STUCK_AFTER = Duration.ofMinutes(15);
    private static final int MAX_ATTEMPTS = 3;
    private static final int NOTIFY_FIRST = 3;

    private static final String K_ENABLED = "threads.enabled";
    private static final String K_TOKEN = "threads.token";
    private static final String K_REFRESHED = "threads.token.refreshed";
    private static final String K_REFRESH_ATTEMPT = "threads.token.attempt";
    private static final String K_REFRESH_FAILS = "threads.token.fails";

    private final ThreadsPostLogRepository logRepository;
    private final AppSettingRepository settingRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${THREADS_ENABLED:false}")
    private boolean envEnabled;
    @Value("${THREADS_USER_ID:}")
    private String userId;
    @Value("${THREADS_ACCESS_TOKEN:}")
    private String envToken;
    @Value("${THREADS_LINK:https://t.me/invitetogamebot}")
    private String botLink;
    @Value("${THREADS_CHANNEL_LINK:https://t.me/exgamingclub}")
    private String channelLink;
    @Value("${THREADS_PAYOUTS_LINK:https://t.me/egc_payouts}")
    private String payoutsLink;
    @Value("${THREADS_VPN_LINK:https://t.me/egc_vpn_bot}")
    private String vpnLink;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, Integer> attempts = new HashMap<>();
    private List<JsonNode> cachedPosts;

    public record Plan(String key, LocalDateTime at, String rubric, String text, String reply, String image) {}

    // ---------------------------------------------------------------- настройки

    public boolean isEnabled() {
        String v = getSetting(K_ENABLED);
        if (v != null && !v.isBlank()) return Boolean.parseBoolean(v.trim());
        return envEnabled;
    }

    public void setEnabled(boolean on) {
        putSetting(K_ENABLED, String.valueOf(on));
    }

    public boolean isConfigured() {
        return userId != null && !userId.isBlank() && token() != null && !token().isBlank();
    }

    private String token() {
        String t = getSetting(K_TOKEN);
        return (t != null && !t.isBlank()) ? t.trim() : envToken;
    }

    private String getSetting(String key) {
        return settingRepository.findById(key).map(AppSetting::getValue).orElse(null);
    }

    private void putSetting(String key, String value) {
        AppSetting s = settingRepository.findById(key).orElseGet(() -> {
            AppSetting n = new AppSetting();
            n.setKey(key);
            return n;
        });
        s.setValue(value);
        settingRepository.save(s);
    }

    // ---------------------------------------------------------------- план

    public synchronized List<Plan> plans() {
        List<Plan> out = new ArrayList<>();
        try {
            if (cachedPosts == null) {
                try (InputStream is = getClass().getResourceAsStream(RESOURCE)) {
                    if (is == null) {
                        log.warn("[Threads] resource {} not found", RESOURCE);
                        return out;
                    }
                    List<JsonNode> list = new ArrayList<>();
                    objectMapper.readTree(is).forEach(list::add);
                    cachedPosts = list;
                }
            }
            for (JsonNode n : cachedPosts) {
                if (n.path("disabled").asBoolean(false)) continue;
                String key = n.path("key").asText("");
                String text = n.path("text").asText("");
                String at = n.path("at").asText("");
                if (key.isBlank() || text.isBlank() || at.isBlank()) continue;
                String reply = n.path("reply").asText("");
                String image = n.path("image").asText("");
                out.add(new Plan(key, LocalDateTime.parse(at), n.path("rubric").asText(""),
                        subst(text), reply.isBlank() ? null : subst(reply), image.isBlank() ? null : image));
            }
        } catch (Exception e) {
            log.warn("[Threads] failed to read plan: {}", e.getMessage());
        }
        out.sort(Comparator.comparing(Plan::at));
        return out;
    }

    private String subst(String s) {
        return s.replace("{{LINK}}", botLink).replace("{{CHANNEL}}", channelLink).replace("{{PAYOUTS}}", payoutsLink).replace("{{VPN}}", vpnLink);
    }

    // ---------------------------------------------------------------- тик

    @Scheduled(fixedDelay = 300_000, initialDelay = 240_000)
    public void tick() {
        try {
            if (!isEnabled() || !isConfigured()) return;
            refreshTokenIfNeeded();
            failStuck();
            publishPendingReplies();
            publishNextPost();
        } catch (Exception e) {
            log.warn("[Threads] tick failed: {}", e.getMessage());
        }
    }

    private void failStuck() {
        LocalDateTime limit = LocalDateTime.now().minus(STUCK_AFTER);
        for (ThreadsPostLog l : logRepository.findByStatus(ThreadsPostLog.PENDING)) {
            if (l.getCreatedAt().isBefore(limit)) {
                l.setStatus(ThreadsPostLog.FAILED);
                l.setError("Зависла в PENDING: неизвестно, ушёл ли пост. Проверьте ленту Threads, повторно сам не отправлю.");
                logRepository.save(l);
                alert("⚠️ <b>Threads: пост " + esc(l.getPostKey()) + " завис</b>\n\nНе знаю, вышел ли он. Посмотрите ленту аккаунта: если поста нет, опубликуйте его вручную. Повторно бот его не отправит.");
            }
        }
    }

    private void publishNextPost() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<ThreadsPostLog> all = logRepository.findAll();
        Map<String, ThreadsPostLog> byKey = new HashMap<>();
        LocalDateTime lastPublished = null;
        for (ThreadsPostLog l : all) {
            byKey.put(l.getPostKey(), l);
            if (ThreadsPostLog.PUBLISHED.equals(l.getStatus()) && l.getPublishedAt() != null
                    && (lastPublished == null || l.getPublishedAt().isAfter(lastPublished))) {
                lastPublished = l.getPublishedAt();
            }
        }
        // PENDING без завершения: не начинаем новый пост, пока не разобрались
        if (all.stream().anyMatch(l -> ThreadsPostLog.PENDING.equals(l.getStatus()))) return;

        Plan next = null;
        for (Plan p : plans()) {
            if (byKey.containsKey(p.key())) continue;
            if (p.at().isAfter(now)) break;
            if (Duration.between(p.at(), now).compareTo(MAX_OVERDUE) > 0) {
                ThreadsPostLog skipped = new ThreadsPostLog();
                skipped.setPostKey(p.key());
                skipped.setStatus(ThreadsPostLog.SKIPPED);
                skipped.setError("Опоздал больше чем на 12 часов");
                logRepository.save(skipped);
                continue;
            }
            next = p;
            break;
        }
        if (next == null) return;
        if (lastPublished != null && Duration.between(lastPublished, LocalDateTime.now()).compareTo(MIN_GAP) < 0) return;

        ThreadsPostLog entry = new ThreadsPostLog();
        entry.setPostKey(next.key());
        entry = logRepository.save(entry);
        try {
            String id = createAndPublish(next.text(), next.image(), null);
            entry.setStatus(ThreadsPostLog.PUBLISHED);
            entry.setThreadsId(id);
            entry.setPublishedAt(LocalDateTime.now());
            entry.setReplyPending(next.reply() != null);
            logRepository.save(entry);
            attempts.remove(next.key());
            long published = logRepository.countByStatus(ThreadsPostLog.PUBLISHED);
            log.info("[Threads] published {} -> {}", next.key(), id);
            if (published <= NOTIFY_FIRST) {
                alert("🧵 <b>Threads: опубликован пост " + esc(next.key()) + "</b> (" + esc(next.rubric()) + ")\n\n" + esc(shorten(next.text(), 200))
                        + "\n\nПервые " + NOTIFY_FIRST + " публикаций показываю, дальше молча, об ошибках сообщу.");
            }
        } catch (ThreadsException e) {
            int n = attempts.merge(next.key(), 1, Integer::sum);
            if (e.unknownOutcome()) {
                entry.setStatus(ThreadsPostLog.FAILED);
                entry.setError(truncate(e.getMessage()));
                logRepository.save(entry);
                alert("⚠️ <b>Threads: пост " + esc(next.key()) + ", неясный результат</b>\n\n" + esc(truncate(e.getMessage()))
                        + "\n\nПроверьте ленту. Повторно бот не отправит.");
            } else if (n >= MAX_ATTEMPTS) {
                entry.setStatus(ThreadsPostLog.FAILED);
                entry.setError(truncate(e.getMessage()));
                logRepository.save(entry);
                alert("⚠️ <b>Threads: пост " + esc(next.key()) + " не ушёл после " + MAX_ATTEMPTS + " попыток</b>\n\n" + esc(truncate(e.getMessage()))
                        + "\n\nЧаще всего это просроченный токен. Остальные посты продолжат выходить по плану.");
            } else {
                // пост точно не вышел (ошибка до публикации): снимаем запись, следующий тик повторит
                logRepository.delete(entry);
                log.warn("[Threads] attempt {} for {} failed: {}", n, next.key(), e.getMessage());
            }
        }
    }

    private void publishPendingReplies() {
        Map<String, Plan> plans = new HashMap<>();
        for (Plan p : plans()) plans.put(p.key(), p);
        for (ThreadsPostLog l : logRepository.findByReplyPendingTrue()) {
            Plan p = plans.get(l.getPostKey());
            l.setReplyPending(false); // одна попытка: ответ-ссылка не должен задвоиться
            logRepository.save(l);
            if (p == null || p.reply() == null || l.getThreadsId() == null) continue;
            try {
                createAndPublish(p.reply(), null, l.getThreadsId());
            } catch (ThreadsException e) {
                alert("⚠️ <b>Threads: ответ со ссылкой под постом " + esc(l.getPostKey()) + " не вышел</b>\n\n" + esc(truncate(e.getMessage()))
                        + "\n\nСам пост опубликован. При желании добавьте ссылку вручную:\n" + esc(p.reply()));
            }
        }
    }

    // ---------------------------------------------------------------- API

    private static class ThreadsException extends Exception {
        private final boolean unknownOutcome;
        ThreadsException(String msg, boolean unknownOutcome) {
            super(msg);
            this.unknownOutcome = unknownOutcome;
        }
        boolean unknownOutcome() { return unknownOutcome; }
    }

    /** Создаёт контейнер и публикует его. Ошибка на создании контейнера: пост точно не вышел; ошибка на публикации: исход неизвестен. */
    private String createAndPublish(String text, String imageUrl, String replyToId) throws ThreadsException {
        Map<String, String> p = new HashMap<>();
        if (imageUrl != null) {
            p.put("media_type", "IMAGE");
            p.put("image_url", imageUrl);
        } else {
            p.put("media_type", "TEXT");
        }
        p.put("text", text);
        if (replyToId != null) p.put("reply_to_id", replyToId);
        String containerId = call("POST", API + "/" + userId + "/threads", p, false).path("id").asText("");
        if (containerId.isBlank()) throw new ThreadsException("Threads не вернул id контейнера", false);

        // текст готов сразу, картинке нужно время: ждём статус FINISHED (до ~60 секунд)
        waitForContainer(containerId, imageUrl != null ? 12 : 1);

        Map<String, String> pub = new HashMap<>();
        pub.put("creation_id", containerId);
        String id = call("POST", API + "/" + userId + "/threads_publish", pub, true).path("id").asText("");
        if (id.isBlank()) throw new ThreadsException("Threads не вернул id опубликованного поста", true);
        return id;
    }

    private void waitForContainer(String containerId, int tries) throws ThreadsException {
        for (int i = 0; i < tries; i++) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ThreadsException("Прервано", false);
            }
            JsonNode st = call("GET", API + "/" + containerId + "?fields=status,error_message", Map.of(), false);
            String status = st.path("status").asText("");
            if ("FINISHED".equals(status)) return;
            if ("ERROR".equals(status) || "EXPIRED".equals(status)) {
                throw new ThreadsException("Контейнер " + status + ": " + st.path("error_message").asText(""), false);
            }
        }
    }

    private JsonNode call(String method, String url, Map<String, String> params, boolean publishStep) throws ThreadsException {
        try {
            StringBuilder form = new StringBuilder();
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (form.length() > 0) form.append('&');
                form.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                        .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            }
            if (form.length() > 0) form.append('&');
            form.append("access_token=").append(URLEncoder.encode(token(), StandardCharsets.UTF_8));
            HttpRequest.Builder b = HttpRequest.newBuilder().timeout(Duration.ofSeconds(30));
            if ("POST".equals(method)) {
                b.uri(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form.toString()));
            } else {
                b.uri(URI.create(url + (url.contains("?") ? "&" : "?") + form)).GET();
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = objectMapper.readTree(r.body() == null || r.body().isBlank() ? "{}" : r.body());
            if (r.statusCode() / 100 != 2) {
                String msg = body.path("error").path("message").asText(truncate(r.body()));
                throw new ThreadsException("HTTP " + r.statusCode() + ": " + msg, publishStep && r.statusCode() >= 500);
            }
            return body;
        } catch (ThreadsException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new ThreadsException("Сеть: " + e.getClass().getSimpleName() + " " + e.getMessage(), publishStep);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ThreadsException("Прервано", publishStep);
        }
    }

    // ---------------------------------------------------------------- токен

    private void refreshTokenIfNeeded() {
        long now = System.currentTimeMillis();
        long lastOk = parseLong(getSetting(K_REFRESHED));
        long lastTry = parseLong(getSetting(K_REFRESH_ATTEMPT));
        long okAgeDays = (now - lastOk) / 86_400_000L;
        long tryAgeHours = (now - lastTry) / 3_600_000L;
        boolean due = lastOk == 0 ? tryAgeHours >= 24 : okAgeDays >= 25 && tryAgeHours >= 24;
        if (!due) return;
        putSetting(K_REFRESH_ATTEMPT, String.valueOf(now));
        try {
            HttpRequest req = HttpRequest.newBuilder().timeout(Duration.ofSeconds(30))
                    .uri(URI.create("https://graph.threads.net/refresh_access_token?grant_type=th_refresh_token&access_token="
                            + URLEncoder.encode(token(), StandardCharsets.UTF_8)))
                    .GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode body = objectMapper.readTree(r.body() == null || r.body().isBlank() ? "{}" : r.body());
            String fresh = body.path("access_token").asText("");
            if (r.statusCode() / 100 == 2 && !fresh.isBlank()) {
                putSetting(K_TOKEN, fresh);
                putSetting(K_REFRESHED, String.valueOf(now));
                putSetting(K_REFRESH_FAILS, "0");
                log.info("[Threads] token refreshed");
                return;
            }
            onRefreshFailed(body.path("error").path("message").asText("HTTP " + r.statusCode()), lastOk == 0);
        } catch (Exception e) {
            onRefreshFailed(e.getClass().getSimpleName() + " " + e.getMessage(), lastOk == 0);
        }
    }

    private void onRefreshFailed(String reason, boolean firstTime) {
        int fails = (int) parseLong(getSetting(K_REFRESH_FAILS)) + 1;
        putSetting(K_REFRESH_FAILS, String.valueOf(fails));
        log.warn("[Threads] token refresh failed ({}): {}", fails, reason);
        // первый раз Meta может отказать, если токен моложе суток: не тревожим сразу
        if (fails >= 3 || (!firstTime && fails >= 2)) {
            alert("⚠️ <b>Threads: не удаётся продлить токен</b> (" + fails + " раза подряд)\n\n" + esc(truncate(reason))
                    + "\n\nТокен живёт 60 дней. Если он истечёт, публикации остановятся. Нужен новый THREADS_ACCESS_TOKEN в окружении бота.");
        }
    }

    // ---------------------------------------------------------------- статус для /threads

    public String statusText() {
        StringBuilder sb = new StringBuilder("🧵 <b>Threads автопубликация</b>\n\n");
        sb.append("Режим: ").append(isEnabled() ? "✅ включена" : "⏸ выключена").append('\n');
        sb.append("Доступ: ").append(isConfigured() ? "токен и ID заданы" : "⚠️ нет THREADS_USER_ID или THREADS_ACCESS_TOKEN").append('\n');
        long ok = (System.currentTimeMillis() - parseLong(getSetting(K_REFRESHED))) / 86_400_000L;
        sb.append("Токен продлён: ").append(parseLong(getSetting(K_REFRESHED)) == 0 ? "ещё не продлевался ботом" : ok + " дн. назад").append("\n\n");
        List<ThreadsPostLog> all = logRepository.findAll();
        long pub = all.stream().filter(l -> ThreadsPostLog.PUBLISHED.equals(l.getStatus())).count();
        long fail = all.stream().filter(l -> ThreadsPostLog.FAILED.equals(l.getStatus())).count();
        long skip = all.stream().filter(l -> ThreadsPostLog.SKIPPED.equals(l.getStatus())).count();
        sb.append("Опубликовано: ").append(pub).append(", ошибок: ").append(fail).append(", пропущено: ").append(skip).append('\n');
        List<String> done = new ArrayList<>();
        all.forEach(l -> done.add(l.getPostKey()));
        List<Plan> left = plans().stream().filter(p -> !done.contains(p.key())).toList();
        sb.append("В плане осталось: ").append(left.size()).append('\n');
        if (!left.isEmpty()) {
            Plan n = left.get(0);
            sb.append("Следующий: ").append(esc(n.key())).append(", ").append(n.at()).append(" UTC (").append(esc(n.rubric())).append(")\n")
                    .append(esc(shorten(n.text(), 160)));
        }
        return sb.toString();
    }

    /** Ближайшие N постов плана (ещё не попавших в журнал): время UTC+7, рубрика, начало текста. */
    public String upcomingText(int n) {
        List<String> done = new ArrayList<>();
        logRepository.findAll().forEach(l -> done.add(l.getPostKey()));
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Plan p : plans()) {
            if (done.contains(p.key())) continue;
            if (shown++ >= n) break;
            sb.append("• ").append(p.at().plusHours(7).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")))
                    .append(" (").append(esc(p.rubric())).append(p.reply() != null ? ", со ссылкой" : "").append(")\n")
                    .append(esc(shorten(p.text(), 90))).append("\n\n");
        }
        return sb.length() == 0 ? "План закончился." : sb.toString().trim();
    }

    /** Снимает ближайший пост с плана (запись SKIPPED). Возвращает его ключ или null, если постов не осталось. */
    public String skipNext() {
        List<String> done = new ArrayList<>();
        logRepository.findAll().forEach(l -> done.add(l.getPostKey()));
        for (Plan p : plans()) {
            if (done.contains(p.key())) continue;
            ThreadsPostLog l = new ThreadsPostLog();
            l.setPostKey(p.key());
            l.setStatus(ThreadsPostLog.SKIPPED);
            l.setError("Снят админом");
            logRepository.save(l);
            return p.key();
        }
        return null;
    }

    // ---------------------------------------------------------------- утилиты

    private void alert(String html) {
        try {
            eventPublisher.publishEvent(new ThreadsAlertEvent(this, html));
        } catch (Exception e) {
            log.warn("[Threads] alert failed: {}", e.getMessage());
        }
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 400 ? s : s.substring(0, 400);
    }
}
