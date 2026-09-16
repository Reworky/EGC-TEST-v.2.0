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

/** Официальный PUBG API (developer.pubg.com, Krafton) для авто-верификации квестов PUBG PC — по
 *  образцу Dota2ApiService: список последних матчей игрока + разбор каждого нового матча по отдельности,
 *  т.к. у PUBG нет стабильного career-counter'а в открытом API (в отличие от CS2).
 *
 *  Транспорт — JSON:API (не raw JSON, как у Steam/OpenDota): Bearer-токен, Accept: application/vnd.api+json,
 *  базовый URL https://api.pubg.com/shards/steam (платформа PC). Матчи/телеметрия НЕ лимитированы (rate-limits.rst,
 *  проверено первоисточником 2026-09-16) — лимит 10 запросов/мин действует только на /players и /seasons. */
@Slf4j
@Service
public class PubgApiService {

    private static final String BASE_URL = "https://api.pubg.com/shards/steam";
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 500;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final boolean enabled;

    public PubgApiService(@Value("${pubg.api-key:}") String apiKey, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
        this.enabled = apiKey != null && !apiKey.isBlank();
        if (!enabled) {
            log.warn("PubgApiService disabled: PUBG_API_KEY not set");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Transient failure (timeout/429/5xx) after exhausting retries, or 403 (key not authorized). */
    public static class PubgTransientException extends Exception {
        public PubgTransientException(String message) { super(message); }
        public PubgTransientException(String message, Throwable cause) { super(message, cause); }
    }

    public record PlayerLookupResult(String accountId, List<String> matchIds) {}

    public record MatchResult(String matchId, int winPlace, int kills, double damageDealt, java.time.LocalDateTime createdAt) {}

    /** Поиск игрока по нику — используется только один раз, на шаге привязки аккаунта. Дальше опрос идёт
     *  по устойчивому accountId (см. fetchRecentMatchIds), не по нику, т.к. ник можно сменить. */
    public Optional<PlayerLookupResult> lookupPlayer(String nickname) throws PubgTransientException {
        String encoded = URLEncoder.encode(nickname, StandardCharsets.UTF_8);
        String url = BASE_URL + "/players?filter[playerNames]=" + encoded;
        JsonNode root = fetchJson(url);
        if (root == null) return Optional.empty();
        return parsePlayerNode(root.path("data").isArray() && !root.path("data").isEmpty()
                ? root.path("data").get(0) : null);
    }

    /** То же самое, по уже привязанному accountId — не зависит от текущего ника игрока. */
    public Optional<PlayerLookupResult> fetchRecentMatches(String accountId) throws PubgTransientException {
        String url = BASE_URL + "/players/" + accountId;
        JsonNode root = fetchJson(url);
        if (root == null) return Optional.empty();
        return parsePlayerNode(root.path("data"));
    }

    private Optional<PlayerLookupResult> parsePlayerNode(JsonNode playerNode) {
        if (playerNode == null || playerNode.isMissingNode()) return Optional.empty();
        String accountId = playerNode.path("id").asText(null);
        if (accountId == null || accountId.isBlank()) return Optional.empty();
        List<String> matchIds = new ArrayList<>();
        for (JsonNode m : playerNode.path("relationships").path("matches").path("data")) {
            String id = m.path("id").asText(null);
            if (id != null) matchIds.add(id);
        }
        return Optional.of(new PlayerLookupResult(accountId, matchIds));
    }

    /** Разбор одного матча (не лимитировано) — ищет participant-объект нужного игрока в "included"
     *  и возвращает его winPlace (1 = победа), kills и damageDealt за этот матч. Optional.empty() —
     *  телеметрия матча ещё не готова (очень редко) либо игрок не найден в составе (не должно
     *  случаться при корректном accountId). */
    public Optional<MatchResult> fetchMatch(String matchId, String accountId) throws PubgTransientException {
        String url = BASE_URL + "/matches/" + matchId;
        JsonNode root = fetchJson(url);
        if (root == null) return Optional.empty();
        String createdAtStr = root.path("data").path("attributes").path("createdAt").asText(null);
        java.time.LocalDateTime createdAt = createdAtStr != null
                ? java.time.LocalDateTime.parse(createdAtStr, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                : null;
        for (JsonNode included : root.path("included")) {
            if (!"participant".equals(included.path("type").asText())) continue;
            JsonNode stats = included.path("attributes").path("stats");
            if (accountId.equals(stats.path("playerId").asText(null))) {
                int winPlace = stats.path("winPlace").asInt(Integer.MAX_VALUE);
                int kills = stats.path("kills").asInt(0);
                double damageDealt = stats.path("damageDealt").asDouble(0);
                return Optional.of(new MatchResult(matchId, winPlace, kills, damageDealt, createdAt));
            }
        }
        return Optional.empty();
    }

    private JsonNode fetchJson(String url) throws PubgTransientException {
        if (!enabled) {
            throw new IllegalStateException("PubgApiService is disabled (no API key configured)");
        }
        PubgTransientException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Accept", "application/vnd.api+json")
                        .GET()
                        .build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 200) {
                    return objectMapper.readTree(resp.body());
                }
                if (resp.statusCode() == 404 || resp.statusCode() == 400) {
                    return null;
                }
                if (resp.statusCode() == 403) {
                    log.error("PUBG API 403 Forbidden — check PUBG_API_KEY validity");
                    throw new PubgTransientException("403 Forbidden — key not authorized");
                }
                lastError = new PubgTransientException("HTTP " + resp.statusCode() + ": " + resp.body());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                lastError = new PubgTransientException("Network error calling PUBG API", e);
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
}
