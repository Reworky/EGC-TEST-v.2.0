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
 * Официальный API Clash Royale (developer.clashroyale.com) — тот же издатель Supercell, тот же паттерн
 * ключа (привязан к статическому IP сервера), что и Brawl Stars / Clash of Clans.
 */
@Slf4j
@Service
public class ClashRoyaleApiService {

    private static final String PLAYER_URL = "https://api.clashroyale.com/v1/players/%s";
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 500;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiToken;
    private final boolean enabled;

    public ClashRoyaleApiService(@Value("${clashroyale.api-token:}") String apiToken, ObjectMapper objectMapper) {
        this.apiToken = apiToken;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
        this.enabled = apiToken != null && !apiToken.isBlank();
        if (!enabled) {
            log.warn("ClashRoyaleApiService disabled: CLASH_ROYALE_API_TOKEN not set");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * trophies/wins/threeCrownWins/warDayWins/totalDonations/battleCount — top-level поля ответа
     * /players/{tag}. НЕ ПРОВЕРЕНО на живом ответе API (нет токена на момент написания) — при первом
     * реальном запуске обязательно сверить точные имена, та же оговорка, что была для Clash of Clans
     * до подтверждения токена и для battlelog Brawl Stars до его сверки.
     */
    public record PlayerInfo(String tag, String name, int trophies, int wins, int threeCrownWins,
                              int warDayWins, int totalDonations, int battleCount) {}

    public static class ClashRoyaleApiTransientException extends Exception {
        public ClashRoyaleApiTransientException(String message) { super(message); }
        public ClashRoyaleApiTransientException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Returns empty for a definitive "tag not found / malformed" result (404/400) — do not retry that.
     * Throws ClashRoyaleApiTransientException after exhausting retries on network error / 429 / 5xx,
     * or immediately on 403 (token not authorized for this IP).
     */
    public Optional<PlayerInfo> fetchPlayer(String rawTag) throws ClashRoyaleApiTransientException {
        if (!enabled) {
            throw new IllegalStateException("ClashRoyaleApiService is disabled (no API token configured)");
        }
        String normalizedTag = normalizeTag(rawTag);
        String url = String.format(PLAYER_URL, URLEncoder.encode("#" + normalizedTag, StandardCharsets.UTF_8));

        ClashRoyaleApiTransientException lastError = null;
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
                    log.error("Clash Royale API 403 Forbidden — check token/IP allowlist registration in Supercell developer portal");
                    throw new ClashRoyaleApiTransientException("403 Forbidden — token/IP not authorized");
                }
                lastError = new ClashRoyaleApiTransientException("HTTP " + resp.statusCode() + ": " + resp.body());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                lastError = new ClashRoyaleApiTransientException("Network error calling Clash Royale API", e);
            }
            if (attempt < MAX_ATTEMPTS) {
                sleepBackoff(attempt);
            }
        }
        throw lastError;
    }

    private PlayerInfo parsePlayer(String body, String selfTag) throws ClashRoyaleApiTransientException {
        try {
            JsonNode node = objectMapper.readTree(body);
            return new PlayerInfo(
                    node.path("tag").asText(selfTag),
                    node.path("name").asText(""),
                    node.path("trophies").asInt(0),
                    node.path("wins").asInt(0),
                    node.path("threeCrownWins").asInt(0),
                    node.path("warDayWins").asInt(0),
                    node.path("totalDonations").asInt(0),
                    node.path("battleCount").asInt(0));
        } catch (Exception e) {
            throw new ClashRoyaleApiTransientException("Failed to parse Clash Royale player response", e);
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
