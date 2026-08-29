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

    private static final String BATTLELOG_URL = "https://api.brawlstars.com/v1/players/%s/battlelog";

    public record BattleLogEntry(
            String battleTime,        // "20260115T120000.000Z" — sortable lexically
            String mode,
            String type,
            boolean victory,
            boolean isTeamMode,
            String playerBrawlerName  // the queried tag's own brawler in this battle
    ) {}

    /**
     * Returns the player's last ~25 battles (empty list on 404/400 — tag not found, not retried).
     * PARSING NOTE — verified against a live response before this is trusted for anything beyond
     * best-effort: exact mode/type string casing, whether "result" is top-level for every non-Showdown
     * mode, and how Showdown/Duels (no top-level result) express a win via player rank. See the
     * verification step in the implementation plan before relying on brawlModeKeys/brawlBrawlerNames
     * matching in QuestSeeder.
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
                boolean isTeamMode = battle.has("teams") && battle.path("teams").size() > 0;
                if (isTeamMode) {
                    for (JsonNode team : battle.path("teams")) {
                        for (JsonNode player : team) {
                            if (selfTag.equalsIgnoreCase(player.path("tag").asText(""))) ownEntry = player;
                        }
                    }
                } else {
                    for (JsonNode player : battle.path("players")) {
                        if (selfTag.equalsIgnoreCase(player.path("tag").asText(""))) ownEntry = player;
                    }
                }
                if (ownEntry == null) continue; // could not locate self in this entry — skip defensively

                String brawlerName = ownEntry.path("brawler").path("name").asText(null);
                boolean victory;
                if (battle.has("result")) {
                    victory = "victory".equalsIgnoreCase(battle.path("result").asText(""));
                } else {
                    // Showdown/Duels-style: no top-level result, win = rank 1
                    victory = ownEntry.path("rank").asInt(Integer.MAX_VALUE) == 1;
                }
                out.add(new BattleLogEntry(battleTime, mode, type, victory, isTeamMode, brawlerName));
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
}
