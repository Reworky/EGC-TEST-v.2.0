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

/** Реклама AdsGram прямо в боте (не в мини-аппе) — отдельный формат "Bot" со своим Block ID/токеном. */
@Slf4j
@Service
public class AdsgramBotAdService {

    private static final String ADVBOT_URL = "https://api.adsgram.ai/advbot?tgid=%s&blockid=%s&language=ru&token=%s";
    private static final int MAX_ATTEMPTS = 2;
    private static final long BASE_BACKOFF_MS = 400;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiToken;
    private final String blockId;
    private final boolean enabled;

    public AdsgramBotAdService(@Value("${app.adsgram-api-token:}") String apiToken,
                                @Value("${app.adsgram-bot-block-id:}") String blockId,
                                ObjectMapper objectMapper) {
        this.apiToken = apiToken;
        // AdsGram: "Use only the numeric part of the blockid, without the bot- prefix"
        this.blockId = blockId != null ? blockId.replaceFirst("^bot-", "") : blockId;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
        this.enabled = apiToken != null && !apiToken.isBlank() && blockId != null && !blockId.isBlank();
        if (!enabled) {
            log.warn("AdsgramBotAdService disabled: ADSGRAM_API_TOKEN/ADSGRAM_BOT_BLOCK_ID not set");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public record AdContent(String textHtml, String imageUrl, String buttonName, String clickUrl,
                             String buttonRewardName, String rewardUrl) {}

    /** Пустой Optional — реклама временно не заполнилась (нет подходящего рекламодателя) либо сбой сети. */
    public Optional<AdContent> fetchAd(Long telegramId) {
        if (!enabled) {
            return Optional.empty();
        }
        String url = String.format(ADVBOT_URL, telegramId,
                URLEncoder.encode(blockId, StandardCharsets.UTF_8),
                URLEncoder.encode(apiToken, StandardCharsets.UTF_8));

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return parseAd(resp.body());
                }
                log.warn("AdsGram advbot returned HTTP {} for tgid={}", resp.statusCode(), telegramId);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("AdsGram advbot request failed (attempt {}/{})", attempt, MAX_ATTEMPTS, e);
            }
            if (attempt < MAX_ATTEMPTS) {
                sleepBackoff(attempt);
            }
        }
        return Optional.empty();
    }

    private Optional<AdContent> parseAd(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            String textHtml = node.path("text_html").asText(null);
            String clickUrl = node.path("click_url").asText(null);
            if (textHtml == null || clickUrl == null) {
                return Optional.empty();
            }
            return Optional.of(new AdContent(
                    textHtml,
                    node.path("image_url").asText(null),
                    node.path("button_name").asText("Перейти"),
                    clickUrl,
                    node.path("button_reward_name").asText("Забрать награду"),
                    node.path("reward_url").asText(null)));
        } catch (Exception e) {
            log.warn("Failed to parse AdsGram advbot response", e);
            return Optional.empty();
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
