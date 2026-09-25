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
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Официальный API Clash of Clans (developer.clashofclans.com) — тот же паттерн, что и BrawlStarsApiService
 * (тот же издатель Supercell, идентичная модель ключа: привязан к статическому IP сервера).
 */
@Slf4j
@Service
public class ClashOfClansApiService {

    private static final String PLAYER_URL = "https://api.clashofclans.com/v1/players/%s";
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 500;
    /** См. тот же фикс и его обоснование в BrawlStarsApiService (коммит 9950579) — без явного таймаута
     *  HttpClient.newHttpClient() может зависнуть на TCP-уровне навсегда, а ClashQuestVerificationService.
     *  checkInProgressSubmissions идёт последовательно по всем заявкам в одном потоке: один зависший
     *  запрос блокирует ВЕСЬ батч. Подтверждено на проде (2026-09-22, жалоба игрока BOXING) — у ВСЕХ
     *  13 ожидающих заявок на квест "Выиграй 3 атаки в мультиплеере" (разные игроки) clash_progress_count
     *  оставался 0 без единой ошибки в логе за сутки — тот же класс симптомов, что был у Brawl. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiToken;
    private final boolean enabled;

    public ClashOfClansApiService(@Value("${clashofclans.api-token:}") String apiToken, ObjectMapper objectMapper) {
        this.apiToken = apiToken;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.enabled = apiToken != null && !apiToken.isBlank();
        if (!enabled) {
            log.warn("ClashOfClansApiService disabled: CLASH_OF_CLANS_API_TOKEN not set");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * goldLooted/elixirLooted/multiplayerWins — накопленные за ВСЮ историю аккаунта значения из
     * achievements "Gold Grab"/"Elixir Escapade"/"Conqueror" (0, если ачивка не нашлась в ответе —
     * не должно происходить в норме, но не валим на этом). townHallLevel/trophies/warStars/donations/
     * defenseWins/expLevel/builderBaseTrophies — top-level поля ответа /players/{tag}, подтверждены
     * живым API (2026-09-22, тикет поддержки #213, игрок BOXING).
     *
     * multiplayerWins (ачивка "Conqueror", "Win 5000 Multiplayer battles") — источник для ATTACK_WINS
     * ВМЕСТО top-level поля attackWins: тот же аудит показал, что attackWins — счётчик за ТЕКУЩИЙ
     * сезон/режим (обнуляется), у активного игрока с ~4600 побед за карьеру он читался 0 при трофеях
     * 110 против bestTrophies 5100 (похоже на сброс, связанный с новым режимом "Рейтинговое сражение" —
     * точный механизм за пределами данных обучения, не угадывается). Ачивка растёт монотонно, того же
     * паттерна, что уже применён для RESOURCES (Gold Grab/Elixir Escapade) по той же причине раньше.
     * defenseWins/trophies — та же семья "текущий период" полей, что и attackWins, потенциально
     * подвержены тому же сбросу, но НЕ переключены здесь — нет живого подтверждения (в отличие от
     * attackWins, где инцидент был подтверждён явно), см. клубную память по инциденту 2026-09-22.
     */
    public record PlayerInfo(String tag, String name, int townHallLevel, int multiplayerWins, int goldLooted, int elixirLooted,
                              int trophies, int warStars, int donations, int defenseWins, int expLevel, int builderBaseTrophies,
                              int builderHallLevel, int heroLevels, int troopLevels, java.util.Map<String, Integer> achievements) {

        /** Накопительное значение ачивки за всю историю аккаунта (0, если такой ачивки нет в ответе). */
        public int achievement(String name) {
            if (name == null) return 0;
            return achievements.getOrDefault(name.toLowerCase(), 0);
        }
    }

    /** Ачивки, на которые переведены донаты и защиты (2026-09-26): top-level donations/defenseWins - счётчики ТЕКУЩЕГО сезона
     *  и обнуляются (проба 2026-09-25: donations=0 при "Friend in Need"=130, defenseWins=0 при "Unbreakable"=10). */
    public static final String ACH_DONATIONS = "Friend in Need";
    public static final String ACH_DEFENSES = "Unbreakable";

    public static class ClashApiTransientException extends Exception {
        public ClashApiTransientException(String message) { super(message); }
        public ClashApiTransientException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Returns empty for a definitive "tag not found / malformed" result (404/400) — do not retry that.
     * Throws ClashApiTransientException after exhausting retries on network error / 429 / 5xx,
     * or immediately on 403 (token not authorized for this IP).
     */
    public Optional<PlayerInfo> fetchPlayer(String rawTag) throws ClashApiTransientException {
        if (!enabled) {
            throw new IllegalStateException("ClashOfClansApiService is disabled (no API token configured)");
        }
        String normalizedTag = normalizeTag(rawTag);
        String url = String.format(PLAYER_URL, URLEncoder.encode("#" + normalizedTag, StandardCharsets.UTF_8));

        ClashApiTransientException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + apiToken)
                        .timeout(REQUEST_TIMEOUT)
                        .GET().build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 200) {
                    return Optional.of(parsePlayer(resp.body(), "#" + normalizedTag));
                }
                if (resp.statusCode() == 404 || resp.statusCode() == 400) {
                    return Optional.empty();
                }
                if (resp.statusCode() == 403) {
                    log.error("Clash of Clans API 403 Forbidden — check token/IP allowlist registration in Supercell developer portal");
                    throw new ClashApiTransientException("403 Forbidden — token/IP not authorized");
                }
                lastError = new ClashApiTransientException("HTTP " + resp.statusCode() + ": " + resp.body());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                lastError = new ClashApiTransientException("Network error calling Clash of Clans API", e);
            }
            if (attempt < MAX_ATTEMPTS) {
                sleepBackoff(attempt);
            }
        }
        throw lastError;
    }

    private PlayerInfo parsePlayer(String body, String selfTag) throws ClashApiTransientException {
        try {
            JsonNode node = objectMapper.readTree(body);
            int goldLooted = 0;
            int elixirLooted = 0;
            int multiplayerWins = 0;
            java.util.Map<String, Integer> achievements = new java.util.HashMap<>();
            for (JsonNode achievement : node.path("achievements")) {
                String name = achievement.path("name").asText("");
                if (!name.isEmpty()) achievements.merge(name.toLowerCase(), achievement.path("value").asInt(0), Math::max);
                if ("Gold Grab".equalsIgnoreCase(name)) {
                    goldLooted = achievement.path("value").asInt(0);
                } else if ("Elixir Escapade".equalsIgnoreCase(name)) {
                    elixirLooted = achievement.path("value").asInt(0);
                } else if ("Conqueror".equalsIgnoreCase(name)) {
                    multiplayerWins = achievement.path("value").asInt(0);
                }
            }
            return new PlayerInfo(
                    node.path("tag").asText(selfTag),
                    node.path("name").asText(""),
                    node.path("townHallLevel").asInt(0),
                    multiplayerWins,
                    goldLooted,
                    elixirLooted,
                    node.path("trophies").asInt(0),
                    node.path("warStars").asInt(0),
                    node.path("donations").asInt(0),
                    node.path("defenseWins").asInt(0),
                    node.path("expLevel").asInt(0),
                    node.path("builderBaseTrophies").asInt(node.path("versusTrophies").asInt(0)),
                    node.path("builderHallLevel").asInt(0),
                    sumLevels(node.path("heroes")),
                    sumLevels(node.path("troops")) + sumLevels(node.path("spells")),
                    achievements);
        } catch (Exception e) {
            throw new ClashApiTransientException("Failed to parse Clash of Clans player response", e);
        }
    }

    private static int sumLevels(JsonNode list) {
        int sum = 0;
        for (JsonNode item : list) sum += item.path("level").asInt(0);
        return sum;
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
}
