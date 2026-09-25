package ru.gamebot.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class BrawlStarsApiService {

    private static final String PLAYER_URL = "https://api.brawlstars.com/v1/players/%s";
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 500;
    /** Без явного таймаута HttpClient.newHttpClient() может зависнуть на TCP-уровне на неопределённое
     *  время (нет ни connect-, ни request-таймаута по умолчанию) — а checkInProgressSubmissions идёт
     *  ПОСЛЕДОВАТЕЛЬНО по всем заявкам в одном потоке шедулера. Один "зависший" запрос без таймаута
     *  блокирует весь батч навсегда: остальные заявки (в т.ч. других игроков) просто никогда не
     *  доходят до проверки, а следующий запуск @Scheduled(fixedDelay=...) не стартует, пока текущий
     *  не завершится — итог неотличим от "сервис молча перестал работать" (инцидент 2026-09-22:
     *  у игрока brawl_battle_cursor оставался NULL сутки — ни одной успешной, ни одной залогированной
     *  ошибочной попытки после единственного всплеска сетевых ошибок). Явные таймауты гарантируют,
     *  что зависшая попытка сама себя оборвёт (IOException -> уже обрабатывается retry/catch ниже),
     *  а батч продолжит идти дальше. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiToken;
    private final boolean enabled;

    public BrawlStarsApiService(@Value("${brawlstars.api-token:}") String apiToken, ObjectMapper objectMapper) {
        this.apiToken = apiToken;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.enabled = apiToken != null && !apiToken.isBlank();
        if (!enabled) {
            log.warn("BrawlStarsApiService disabled: BRAWL_STARS_API_TOKEN not set");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Поля из профиля игрока (players/{tag}, подтверждено живым вызовом 2026-09-25): powerSum/rankSum - суммы power/rank
     *  по всем бойцам, unlocks - число открытых гаджетов+звёздных сил+снаряжения+гиперзарядов; все растут монотонно. */
    public record PlayerInfo(String tag, String name, int trophies, int expLevel,
                             int powerSum, int rankSum, int unlocks) {}

    /** Transient failure (timeout/429/5xx) after exhausting retries, or a 403 (token/IP misconfigured). */
    public static class BrawlStarsTransientException extends Exception {
        public BrawlStarsTransientException(String message) { super(message); }
        public BrawlStarsTransientException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Returns empty for a definitive "tag not found / malformed" result (404/400) — do not retry that.
     * Throws BrawlStarsTransientException after exhausting retries on network error / 429 / 5xx,
     * or immediately on 403 (token not authorized for this IP).
     */
    public Optional<PlayerInfo> fetchPlayer(String rawTag) throws BrawlStarsTransientException {
        String normalizedTag = normalizeTag(rawTag);
        return fetchPlayerNode(rawTag).map(node -> {
            int powerSum = 0;
            int rankSum = 0;
            int unlocks = 0;
            for (JsonNode b : node.path("brawlers")) {
                powerSum += b.path("power").asInt(0);
                rankSum += b.path("rank").asInt(0);
                unlocks += b.path("gadgets").size() + b.path("starPowers").size()
                        + b.path("gears").size() + b.path("hyperCharges").size();
            }
            return new PlayerInfo(
                    node.path("tag").asText("#" + normalizedTag),
                    node.path("name").asText(""),
                    node.path("trophies").asInt(0),
                    node.path("expLevel").asInt(0),
                    powerSum, rankSum, unlocks);
        });
    }

    /** NEW_BRAWLER: имена всех бойцов, которыми игрок уже владеет (поле "brawlers" в ответе players/{tag}). */
    public java.util.Set<String> fetchOwnedBrawlerNames(String rawTag) throws BrawlStarsTransientException {
        Optional<JsonNode> node = fetchPlayerNode(rawTag);
        if (node.isEmpty()) return java.util.Set.of();
        java.util.Set<String> names = new java.util.HashSet<>();
        for (JsonNode b : node.get().path("brawlers")) {
            String n = b.path("name").asText(null);
            if (n != null) names.add(n);
        }
        return names;
    }

    private Optional<JsonNode> fetchPlayerNode(String rawTag) throws BrawlStarsTransientException {
        if (!enabled) {
            throw new IllegalStateException("BrawlStarsApiService is disabled (no API token configured)");
        }
        String normalizedTag = normalizeTag(rawTag);
        String url = String.format(PLAYER_URL, URLEncoder.encode("#" + normalizedTag, StandardCharsets.UTF_8));

        BrawlStarsTransientException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + apiToken)
                        .timeout(REQUEST_TIMEOUT)
                        .GET().build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 200) {
                    return Optional.of(objectMapper.readTree(resp.body()));
                }
                if (resp.statusCode() == 404 || resp.statusCode() == 400) {
                    return Optional.empty();
                }
                if (resp.statusCode() == 403) {
                    log.error("Brawl Stars API 403 Forbidden — check token/IP allowlist registration in Supercell developer portal");
                    throw new BrawlStarsTransientException("403 Forbidden — token/IP not authorized");
                }
                lastError = new BrawlStarsTransientException("HTTP " + resp.statusCode() + ": " + resp.body());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                lastError = new BrawlStarsTransientException("Network error calling Brawl Stars API", e);
            }
            if (attempt < MAX_ATTEMPTS) {
                sleepBackoff(attempt);
            }
        }
        throw lastError;
    }

    private static final String BATTLELOG_URL = "https://api.brawlstars.com/v1/players/%s/battlelog";

    public record BattleLogEntry(
            String battleTime,        // "20260115T120000.000Z" — sortable lexically
            String mode,
            String type,
            boolean victory,
            boolean isTeamMode,
            String playerBrawlerName, // the queried tag's own brawler in this battle
            List<String> teammateTags // other players in the SAME team as self (team-mode only, empty otherwise)
    ) {}

    /**
     * Returns the player's last ~25 battles (empty list on 404/400 — tag not found, not retried).
     * PARSING NOTE — "type" for ranked battles is "teamRanked" or "soloRanked" (camelCase), NEVER a
     * bare "ranked" — confirmed 2026-09-19 after discovering BrawlQuestVerificationService.matchesFilters
     * compared against the literal "ranked" and so requireRanked had NEVER matched a single battle for
     * any player since the filter was introduced. Still best-effort beyond that: exact mode string
     * casing, whether "result" is top-level for every non-Showdown mode, and how Showdown/Duels (no
     * top-level result) express a win via player rank — verify against a live response before relying
     * on brawlModeKeys/brawlBrawlerNames matching in QuestSeeder for anything new.
     */
    public List<BattleLogEntry> fetchBattleLog(String rawTag) throws BrawlStarsTransientException {
        if (!enabled) {
            throw new IllegalStateException("BrawlStarsApiService is disabled (no API token configured)");
        }
        String normalizedTag = normalizeTag(rawTag);
        String url = String.format(BATTLELOG_URL, URLEncoder.encode("#" + normalizedTag, StandardCharsets.UTF_8));

        BrawlStarsTransientException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + apiToken)
                        .timeout(REQUEST_TIMEOUT)
                        .GET().build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 200) {
                    return parseBattleLog(resp.body(), "#" + normalizedTag);
                }
                if (resp.statusCode() == 404 || resp.statusCode() == 400) {
                    return List.of();
                }
                if (resp.statusCode() == 403) {
                    log.error("Brawl Stars API 403 Forbidden — check token/IP allowlist registration in Supercell developer portal");
                    throw new BrawlStarsTransientException("403 Forbidden — token/IP not authorized");
                }
                lastError = new BrawlStarsTransientException("HTTP " + resp.statusCode() + ": " + resp.body());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                lastError = new BrawlStarsTransientException("Network error calling Brawl Stars API", e);
            }
            if (attempt < MAX_ATTEMPTS) {
                sleepBackoff(attempt);
            }
        }
        throw lastError;
    }

    private List<BattleLogEntry> parseBattleLog(String body, String selfTag) {
        List<BattleLogEntry> out = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(body);
            for (JsonNode item : root.path("items")) {
                String battleTime = item.path("battleTime").asText(null);
                JsonNode battle = item.path("battle");
                String mode = battle.path("mode").asText(item.path("event").path("mode").asText(null));
                String type = battle.path("type").asText(null);
                if (battleTime == null || mode == null) continue;

                JsonNode ownEntry = null;
                List<String> teammateTags = new ArrayList<>();
                boolean isTeamMode = battle.has("teams") && battle.path("teams").size() > 0;
                if (isTeamMode) {
                    for (JsonNode team : battle.path("teams")) {
                        boolean selfInThisTeam = false;
                        for (JsonNode player : team) {
                            if (tagsLikelyEqual(selfTag, player.path("tag").asText(""))) {
                                ownEntry = player;
                                selfInThisTeam = true;
                            }
                        }
                        if (selfInThisTeam) {
                            for (JsonNode player : team) {
                                String t = player.path("tag").asText("");
                                if (!tagsLikelyEqual(selfTag, t)) teammateTags.add(t);
                            }
                        }
                    }
                } else {
                    for (JsonNode player : battle.path("players")) {
                        if (tagsLikelyEqual(selfTag, player.path("tag").asText(""))) ownEntry = player;
                    }
                }
                if (ownEntry == null) continue; // could not locate self in this entry — skip defensively

                String brawlerName = ownEntry.path("brawler").path("name").asText(null);
                boolean victory;
                if (battle.has("result")) {
                    victory = "victory".equalsIgnoreCase(battle.path("result").asText(""));
                } else {
                    // Showdown-style (soloShowdown/duoShowdown): no "result", "rank" is on battle itself
                    // (this player's own battlelog, so it's already their placement) — verified live, rank 1 = win.
                    victory = battle.path("rank").asInt(Integer.MAX_VALUE) == 1;
                }
                out.add(new BattleLogEntry(battleTime, mode, type, victory, isTeamMode, brawlerName, teammateTags));
            }
        } catch (Exception e) {
            log.warn("Failed to parse Brawl Stars battlelog response", e);
        }
        return out;
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(BASE_BACKOFF_MS * (1L << (attempt - 1)));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String normalizeTag(String tag) {
        String t = tag.trim().toUpperCase();
        return t.startsWith("#") ? t.substring(1) : t;
    }

    /** Сравнение тега игрока (сохранённого при привязке) с тегом из battlelog — не всегда буквальное
     *  равенство. Подтверждено живым API-ответом (2026-09-22, жалоба игрока Kwish, byte-exact сверка
     *  через дамп JSON, а не на глаз): /players/{tag} резолвит аккаунт даже если в теге буква 'O' стоит
     *  там, где в АВТОРИТЕТНЫХ данных battlelog у того же игрока цифра '0' (и эхом отдаёт тег как
     *  запросили, не как он на самом деле выглядит) — сравнение selfTag.equalsIgnoreCase(...) в этом
     *  случае никогда не совпадало, КАЖДЫЙ бой тихо пропускался, прогресс BATTLES-квестов не считался
     *  вовсе, без единой ошибки в логе. Буква 'O' при этом НЕ всегда опечатка — есть подтверждённые
     *  реальные теги, где 'O' и '0' встречаются в одном теге одновременно (см. память проекта
     *  feedback_tag_confirm_screen, тег #P0Y2GORGV) — поэтому МЕНЯТЬ сохранённый тег или тег в URL-
     *  запросе нельзя, это сломало бы других игроков. Точное сравнение — приоритет; O/0-эквивалентность
     *  — только запасной вариант, когда точное не совпало, и только для решения "это я в этом бою?",
     *  не для чего-либо ещё. */
    private boolean tagsLikelyEqual(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equalsIgnoreCase(b)) return true;
        return a.replace('O', '0').equalsIgnoreCase(b.replace('O', '0'));
    }
}
