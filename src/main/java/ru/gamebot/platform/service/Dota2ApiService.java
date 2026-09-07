package ru.gamebot.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Steam Web API для авто-верификации квестов Dota 2 — по образцу BrawlStarsApiService, но с двумя
 *  отличиями транспортного уровня: ключ передаётся query-параметром (не Bearer header), и получение
 *  данных о матче — двухшаговое (GetMatchHistory даёт список, GetMatchDetails — статистику игрока).
 *
 *  ВАЖНО: точные имена полей в ответе GetMatchDetails (kills/assists/deaths/level/gold/gold_spent/duration)
 *  взяты по документации Steam Web API — сверить живым вызовом до первого прод-деплоя с реальным ключом,
 *  см. implementation-план. Известный риск (принят как есть): GetMatchDetails периодически отдаёт 500
 *  после патчей игры — обрабатывается как транзиентная ошибка с ретраем на следующем цикле поллера. */
@Slf4j
@Service
public class Dota2ApiService {

    private static final String MATCH_HISTORY_URL = "https://api.steampowered.com/IDOTA2Match_570/GetMatchHistory/v1/";
    private static final String MATCH_DETAILS_URL = "https://api.steampowered.com/IDOTA2Match_570/GetMatchDetails/v1/";
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 500;
    private static final long STEAM_ID_64_BASE = 76561197960265728L;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final boolean enabled;

    public Dota2ApiService(@Value("${steam.api-key:}") String apiKey, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
        this.enabled = apiKey != null && !apiKey.isBlank();
        if (!enabled) {
            log.warn("Dota2ApiService disabled: STEAM_API_KEY not set");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public static long steamId64ToAccountId(long steamId64) {
        return steamId64 - STEAM_ID_64_BASE;
    }

    /** Transient failure (timeout/429/5xx) after exhausting retries, or 403 (key not authorized). */
    public static class Dota2TransientException extends Exception {
        public Dota2TransientException(String message) { super(message); }
        public Dota2TransientException(String message, Throwable cause) { super(message, cause); }
    }

    public record MatchSummary(long matchId, long startTime) {}

    public record MatchResult(long matchId, int durationSeconds, int kills, int assists, int deaths,
                               int heroLevel, long goldEarned) {}

    /**
     * Последние матчи аккаунта, отсортированные API от новых к старым (пустой список — аккаунт не найден,
     * либо "Expose Public Match Data" выключена; это одно и то же с точки зрения API, отличить нельзя).
     * startAtMatchId — для пагинации назад по истории (null = самые свежие матчи).
     */
    public List<MatchSummary> fetchRecentMatches(long accountId, Long startAtMatchId) throws Dota2TransientException {
        if (!enabled) {
            throw new IllegalStateException("Dota2ApiService is disabled (no API key configured)");
        }
        StringBuilder url = new StringBuilder(MATCH_HISTORY_URL)
                .append("?key=").append(apiKey)
                .append("&account_id=").append(accountId)
                .append("&matches_requested=25");
        if (startAtMatchId != null) {
            url.append("&start_at_match_id=").append(startAtMatchId);
        }
        JsonNode result = fetchJson(url.toString(), "result");
        if (result == null) return List.of();

        List<MatchSummary> out = new ArrayList<>();
        for (JsonNode m : result.path("matches")) {
            long matchId = m.path("match_id").asLong(-1);
            long startTime = m.path("start_time").asLong(-1);
            if (matchId >= 0 && startTime >= 0) {
                out.add(new MatchSummary(matchId, startTime));
            }
        }
        return out;
    }

    /** null — матч не найден, приватный, либо игрок не участвовал (не должно случаться при корректном accountId). */
    public Optional<MatchResult> fetchMatchDetails(long matchId, long accountId) throws Dota2TransientException {
        if (!enabled) {
            throw new IllegalStateException("Dota2ApiService is disabled (no API key configured)");
        }
        String url = MATCH_DETAILS_URL + "?key=" + apiKey + "&match_id=" + matchId;
        JsonNode result = fetchJson(url, "result");
        if (result == null) return Optional.empty();

        int duration = result.path("duration").asInt(0);
        for (JsonNode player : result.path("players")) {
            if (player.path("account_id").asLong(-1) == accountId) {
                int kills = player.path("kills").asInt(0);
                int assists = player.path("assists").asInt(0);
                int deaths = player.path("deaths").asInt(0);
                int level = player.path("level").asInt(0);
                long gold = player.path("gold").asLong(0);
                long goldSpent = player.path("gold_spent").asLong(0);
                return Optional.of(new MatchResult(matchId, duration, kills, assists, deaths, level, gold + goldSpent));
            }
        }
        return Optional.empty();
    }

    private JsonNode fetchJson(String url, String resultField) throws Dota2TransientException {
        Dota2TransientException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 200) {
                    JsonNode root = objectMapper.readTree(resp.body());
                    JsonNode result = root.path(resultField);
                    // GetMatchHistory/GetMatchDetails возвращают status=15 внутри result при приватном профиле —
                    // трактуем как "нет данных", не как ошибку (не ретраится).
                    if (result.has("status") && result.path("status").asInt(1) != 1) {
                        return null;
                    }
                    return result;
                }
                if (resp.statusCode() == 404 || resp.statusCode() == 400) {
                    return null;
                }
                if (resp.statusCode() == 403) {
                    log.error("Steam API 403 Forbidden — check STEAM_API_KEY validity");
                    throw new Dota2TransientException("403 Forbidden — key not authorized");
                }
                lastError = new Dota2TransientException("HTTP " + resp.statusCode() + ": " + resp.body());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                lastError = new Dota2TransientException("Network error calling Steam API", e);
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
