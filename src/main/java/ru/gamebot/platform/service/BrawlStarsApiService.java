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

@Slf4j
@Service
public class BrawlStarsApiService {

    private static final String PLAYER_URL = "https://api.brawlstars.com/v1/players/%s";
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 500;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiToken;
    private final boolean enabled;

    public BrawlStarsApiService(@Value("${brawlstars.api-token:}") String apiToken, ObjectMapper objectMapper) {
        this.apiToken = apiToken;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
        this.enabled = apiToken != null && !apiToken.isBlank();
        if (!enabled) {
            log.warn("BrawlStarsApiService disabled: BRAWL_STARS_API_TOKEN not set");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public record PlayerInfo(String tag, String name, int trophies) {}

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
                        .GET().build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 200) {
                    JsonNode node = objectMapper.readTree(resp.body());
                    return Optional.of(new PlayerInfo(
                            node.path("tag").asText("#" + normalizedTag),
                            node.path("name").asText(""),
                            node.path("trophies").asInt(0)));
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
