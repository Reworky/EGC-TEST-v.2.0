package ru.gamebot.platform.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.QuestSubmission;

@Slf4j
@Service
public class ClaudeVisionService {

    private static final String TELEGRAM_FILE_API = "https://api.telegram.org/bot%s/getFile?file_id=%s";
    private static final String TELEGRAM_DOWNLOAD  = "https://api.telegram.org/file/bot%s/%s";

    private final AnthropicClient client;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final double confidenceThreshold;
    private final String botToken;
    private final boolean enabled;

    public ClaudeVisionService(
            @Value("${claude.api-key:}") String apiKey,
            @Value("${claude.confidence-threshold:0.85}") double confidenceThreshold,
            @Value("${app.bot-token:}") String botToken,
            ObjectMapper objectMapper) {

        this.confidenceThreshold = confidenceThreshold;
        this.botToken = botToken;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
        this.enabled = apiKey != null && !apiKey.isBlank();

        if (this.enabled) {
            this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
            log.info("ClaudeVisionService initialized, confidence threshold={}", confidenceThreshold);
        } else {
            this.client = null;
            log.warn("ClaudeVisionService disabled: CLAUDE_API_KEY not set");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Verifies a quest submission photo against the quest requirements.
     * Returns null if verification is not possible (service disabled, no image, download error).
     */
    public AiVerificationResult verify(QuestSubmission submission) {
        if (!enabled) return null;
        if (!"photo".equals(submission.getMediaType())) return null;
        if (submission.getMediaFileId() == null || submission.getMediaFileId().isBlank()) return null;

        try {
            byte[] imageBytes = downloadTelegramFile(submission.getMediaFileId());
            if (imageBytes == null || imageBytes.length == 0) return null;

            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String prompt = buildPrompt(submission);

            return callClaudeApi(base64Image, prompt);
        } catch (Exception e) {
            log.warn("AI verification failed for submission {}: {}", submission.getId(), e.getMessage());
            return null;
        }
    }

    private byte[] downloadTelegramFile(String fileId) throws IOException, InterruptedException {
        // Step 1: resolve file_path from Telegram
        String getFileUrl = String.format(TELEGRAM_FILE_API, botToken, fileId);
        HttpResponse<String> metaResp = httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(getFileUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        JsonNode meta = objectMapper.readTree(metaResp.body());
        if (!meta.path("ok").asBoolean()) {
            log.warn("Telegram getFile failed: {}", metaResp.body());
            return null;
        }
        String filePath = meta.path("result").path("file_path").asText();

        // Step 2: download the actual file
        String downloadUrl = String.format(TELEGRAM_DOWNLOAD, botToken, filePath);
        HttpResponse<byte[]> fileResp = httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(downloadUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        return fileResp.body();
    }

    private String buildPrompt(QuestSubmission submission) {
        String gameName = submission.getQuest().getGameName();
        String questTitle = submission.getQuest().getTitle();
        String questDesc = submission.getQuest().getDescription();
        String requirements = submission.getQuest().getRequirements();
        String userComment = submission.getUserComment();

        StringBuilder sb = new StringBuilder();
        sb.append("Ты — модератор игрового квест-платформы EGC. Проверь скриншот отчёта игрока.\n\n");
        sb.append("Игра: ").append(gameName).append("\n");
        sb.append("Квест: ").append(questTitle).append("\n");
        sb.append("Описание квеста: ").append(questDesc).append("\n");
        if (requirements != null && !requirements.isBlank()) {
            sb.append("Требования к доказательству: ").append(requirements).append("\n");
        }
        if (userComment != null && !userComment.isBlank() && !"Без комментария".equals(userComment)) {
            sb.append("Комментарий игрока: ").append(userComment).append("\n");
        }
        sb.append("\n");
        sb.append("Внимательно посмотри на скриншот и реши: выполнен ли квест?\n\n");
        sb.append("Ответь ТОЛЬКО валидным JSON без markdown-блоков, в точно таком формате:\n");
        sb.append("{\"decision\":\"APPROVE\",\"confidence\":0.95,\"reason\":\"Краткое объяснение\",\"checks\":{\"game_visible\":true,\"task_completed\":true,\"screenshot_authentic\":true}}\n\n");
        sb.append("Правила:\n");
        sb.append("- decision: \"APPROVE\" если квест явно выполнен, \"REJECT\" если явно не выполнен, \"MANUAL\" если неясно\n");
        sb.append("- confidence: от 0.0 до 1.0, твоя уверенность в решении\n");
        sb.append("- reason: 1–2 предложения на русском\n");
        sb.append("- checks.game_visible: видна ли нужная игра (").append(gameName).append(")\n");
        sb.append("- checks.task_completed: выполнено ли именно это задание\n");
        sb.append("- checks.screenshot_authentic: нет ли признаков фотошопа или повторного использования чужого скриншота");

        return sb.toString();
    }

    private AiVerificationResult callClaudeApi(String base64Image, String prompt) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(Model.CLAUDE_OPUS_4_8)
                .maxTokens(512L)
                .addUserMessageOfBlockParams(List.of(
                        ContentBlockParam.ofImage(ImageBlockParam.builder()
                                .source(Base64ImageSource.builder()
                                        .data(base64Image)
                                        .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                        .build())
                                .build()),
                        ContentBlockParam.ofText(TextBlockParam.builder()
                                .text(prompt)
                                .build())))
                .build();

        Message response = client.messages().create(params);

        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .findFirst()
                .orElse("");

        return parseResponse(text);
    }

    private AiVerificationResult parseResponse(String text) {
        try {
            // Strip potential markdown fences the model might add despite instructions
            String json = text.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf('{');
                int end = json.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    json = json.substring(start, end + 1);
                }
            }

            JsonNode node = objectMapper.readTree(json);
            String decision = node.path("decision").asText("MANUAL").toUpperCase();
            double confidence = node.path("confidence").asDouble(0.5);
            String reason = node.path("reason").asText("Нет данных");
            String checksJson = objectMapper.writeValueAsString(node.path("checks"));

            // Enforce confidence threshold: low confidence → always MANUAL
            if (confidence < confidenceThreshold && !"MANUAL".equals(decision)) {
                decision = "MANUAL";
            }

            // Sanitize decision
            if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) {
                decision = "MANUAL";
            }

            return new AiVerificationResult(decision, confidence, reason, checksJson);
        } catch (Exception e) {
            log.warn("Failed to parse Claude response: {}", text, e);
            return new AiVerificationResult("MANUAL", 0.0, "Ошибка разбора ответа AI", "{}");
        }
    }
}
