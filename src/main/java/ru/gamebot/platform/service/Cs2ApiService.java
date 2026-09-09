package ru.gamebot.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Steam Web API для авто-верификации квестов CS2 — тот же транспорт, что и Dota2ApiService (ключ
 *  query-параметром, appid 730), одна ручка ISteamUserStats/GetUserStatsForGame отдаёт сразу все
 *  кумулятивные career-счётчики игрока (дельта с момента взятия квеста, как у Clash Royale) — полной
 *  истории отдельных матчей в открытом API для CS2 нет. НО ответ также содержит last_match_* —
 *  статистику именно последнего сыгранного матча (kills/deaths/mvps/contribution_score), что позволяет
 *  проверять условия "за один матч" без полной истории — см. Cs2QuestVerificationService.
 *
 *  Имена полей сверены живым вызовом 2026-09-09 (см. implementation_log.md) — total_kills/total_mvps/
 *  total_kills_headshot/total_planted_bombs/total_defused_bombs/total_matches_played совпали с ожиданием.
 *  ВАЖНОЕ РАСХОЖДЕНИЕ: total_wins считает победы в РАУНДАХ (в связке с total_rounds_played), а не в
 *  матчах — для WINS (побед в матчах) нужно total_matches_won, отдельное поле.
 *  last_match_* поля видены в живом ответе ОДИН раз (одна проверка, один снапшот) — не подтверждено
 *  многократными опросами, что они действительно обновляются после КАЖДОГО нового матча (не только
 *  после сессии/выхода из игры и т.п.) — учитывать при первых реальных прогонах поллера. */
@Slf4j
@Service
public class Cs2ApiService {

    private static final String STATS_URL = "https://api.steampowered.com/ISteamUserStats/GetUserStatsForGame/v2/";
    private static final int CS2_APP_ID = 730;
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 500;
    private static final long STEAM_ID_64_MIN = 76561197960265728L;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final boolean enabled;

    public Cs2ApiService(@Value("${steam.api-key:}") String apiKey, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
        this.enabled = apiKey != null && !apiKey.isBlank();
        if (!enabled) {
            log.warn("Cs2ApiService disabled: STEAM_API_KEY not set");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public static boolean looksLikeSteamId64(long value) {
        return value >= STEAM_ID_64_MIN;
    }

    public static class Cs2ApiTransientException extends Exception {
        public Cs2ApiTransientException(String message) { super(message); }
        public Cs2ApiTransientException(String message, Throwable cause) { super(message, cause); }
    }

    public record PlayerStats(long kills, long wins, long matchesWon, long mvps, long headshots,
                               long bombsPlanted, long bombsDefused, long matchesPlayed,
                               long lastMatchKills, long lastMatchDeaths, long lastMatchMvps, long lastMatchScore) {}

    /** Пусто — приватная статистика (профиль/раздел "Игровая статистика" не публичны) либо у аккаунта
     *  вовсе нет статистики CS2 (игра не куплена/не запущена ни разу). Steam Web API отличает эти случаи
     *  полем "error" в ответе при HTTP 200, не кодом ошибки — обе ситуации трактуем одинаково как "нет данных". */
    public Optional<PlayerStats> fetchStats(long steamId64) throws Cs2ApiTransientException {
        if (!enabled) {
            throw new IllegalStateException("Cs2ApiService is disabled (no API key configured)");
        }
        String url = STATS_URL + "?appid=" + CS2_APP_ID + "&key=" + apiKey + "&steamid=" + steamId64;

        Cs2ApiTransientException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 200) {
                    return parseStats(resp.body());
                }
                if (resp.statusCode() == 404 || resp.statusCode() == 400) {
                    return Optional.empty();
                }
                if (resp.statusCode() == 403) {
                    log.error("Steam API 403 Forbidden — check STEAM_API_KEY validity");
                    throw new Cs2ApiTransientException("403 Forbidden — key not authorized");
                }
                lastError = new Cs2ApiTransientException("HTTP " + resp.statusCode() + ": " + resp.body());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                lastError = new Cs2ApiTransientException("Network error calling Steam API", e);
            }
            if (attempt < MAX_ATTEMPTS) {
                sleepBackoff(attempt);
            }
        }
        throw lastError;
    }

    private Optional<PlayerStats> parseStats(String body) throws Cs2ApiTransientException {
        try {
            JsonNode playerStats = objectMapper.readTree(body).path("playerstats");
            if (playerStats.has("error") || !playerStats.has("stats")) {
                return Optional.empty();
            }
            java.util.Map<String, Long> byName = new java.util.HashMap<>();
            for (JsonNode stat : playerStats.path("stats")) {
                byName.put(stat.path("name").asText(""), stat.path("value").asLong(0));
            }
            return Optional.of(new PlayerStats(
                    byName.getOrDefault("total_kills", 0L),
                    byName.getOrDefault("total_wins", 0L),
                    byName.getOrDefault("total_matches_won", 0L),
                    byName.getOrDefault("total_mvps", 0L),
                    byName.getOrDefault("total_kills_headshot", 0L),
                    byName.getOrDefault("total_planted_bombs", 0L),
                    byName.getOrDefault("total_defused_bombs", 0L),
                    byName.getOrDefault("total_matches_played", 0L),
                    byName.getOrDefault("last_match_kills", 0L),
                    byName.getOrDefault("last_match_deaths", 0L),
                    byName.getOrDefault("last_match_mvps", 0L),
                    byName.getOrDefault("last_match_contribution_score", 0L)));
        } catch (Exception e) {
            throw new Cs2ApiTransientException("Failed to parse CS2 stats response", e);
        }
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(BASE_BACKOFF_MS * (1L << (attempt - 1)));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
