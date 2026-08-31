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

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiToken;
    private final boolean enabled;

    public ClashOfClansApiService(@Value("${clashofclans.api-token:}") String apiToken, ObjectMapper objectMapper) {
        this.apiToken = apiToken;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
        this.enabled = apiToken != null && !apiToken.isBlank();
        if (!enabled) {
            log.warn("ClashOfClansApiService disabled: CLASH_OF_CLANS_API_TOKEN not set");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * goldLooted/elixirLooted — накопленные за ВСЮ историю аккаунта значения из achievements "Gold Grab"/
     * "Elixir Escapade" (0, если ачивка не нашлась в ответе — не должно происходить в норме, но не валим на этом).
     * townHallLevel/attackWins/trophies/warStars/donations/defenseWins/expLevel/builderBaseTrophies —
     * top-level поля ответа /players/{tag}.
     * ЕЩЁ НЕ СВЕРЕНО на живом ответе API — токен уже настроен и задеплоен, но реальный первый прогон
     * (взять квест, привязать тег, дождаться опроса) ещё не подтверждён. Сверить точные названия ачивок
     * и что все перечисленные поля действительно лежат на верхнем уровне, а не вложенно, прежде чем
     * доверять фиче полностью (та же оговорка, что была для battlelog Brawl Stars до его сверки).
     * builderBaseTrophies — в некоторых версиях API называлось versusTrophies, стоит перепроверить.
     */
    public record PlayerInfo(String tag, String name, int townHallLevel, int attackWins, int goldLooted, int elixirLooted,
                              int trophies, int warStars, int donations, int defenseWins, int expLevel, int builderBaseTrophies) {}

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
            for (JsonNode achievement : node.path("achievements")) {
                String name = achievement.path("name").asText("");
                if ("Gold Grab".equalsIgnoreCase(name)) {
                    goldLooted = achievement.path("value").asInt(0);
                } else if ("Elixir Escapade".equalsIgnoreCase(name)) {
                    elixirLooted = achievement.path("value").asInt(0);
                }
            }
            return new PlayerInfo(
                    node.path("tag").asText(selfTag),
                    node.path("name").asText(""),
                    node.path("townHallLevel").asInt(0),
                    node.path("attackWins").asInt(0),
                    goldLooted,
                    elixirLooted,
                    node.path("trophies").asInt(0),
                    node.path("warStars").asInt(0),
                    node.path("donations").asInt(0),
                    node.path("defenseWins").asInt(0),
                    node.path("expLevel").asInt(0),
                    node.path("builderBaseTrophies").asInt(node.path("versusTrophies").asInt(0)));
        } catch (Exception e) {
            throw new ClashApiTransientException("Failed to parse Clash of Clans player response", e);
        }
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
