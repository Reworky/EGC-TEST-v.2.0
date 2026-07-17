package ru.gamebot.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.config.AppProperties;

@Service
@RequiredArgsConstructor
public class TelegramFileService {

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Отправляет фото в чат пользователя через Bot API (sendPhoto) и возвращает file_id.
     * Мост для загрузок из Mini App: там нет своего файлового хранилища — как и везде в проекте,
     * фото хранятся на серверах Telegram, бот хранит только file_id.
     */
    public String uploadPhoto(byte[] imageBytes, String filename, Long chatId) throws IOException, InterruptedException {
        String token = appProperties.getBotToken();
        String boundary = "----EGC" + UUID.randomUUID();

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeField(body, boundary, "chat_id", chatId.toString());

        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"photo\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        body.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(imageBytes);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/bot" + token + "/sendPhoto"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        JsonNode root = objectMapper.readTree(response.body());
        if (!root.path("ok").asBoolean(false)) {
            throw new IOException("Telegram sendPhoto failed: " + response.body());
        }
        JsonNode sizes = root.path("result").path("photo");
        if (!sizes.isArray() || sizes.isEmpty()) {
            throw new IOException("Telegram sendPhoto returned no photo sizes: " + response.body());
        }
        return sizes.get(sizes.size() - 1).path("file_id").asText();
    }

    public String uploadVideo(byte[] videoBytes, String filename, Long chatId) throws IOException, InterruptedException {
        String token = appProperties.getBotToken();
        String boundary = "----EGC" + UUID.randomUUID();

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeField(body, boundary, "chat_id", chatId.toString());

        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"video\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        body.write("Content-Type: application/octet-stream\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(videoBytes);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/bot" + token + "/sendVideo"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        JsonNode root = objectMapper.readTree(response.body());
        if (!root.path("ok").asBoolean(false)) {
            throw new IOException("Telegram sendVideo failed: " + response.body());
        }
        return root.path("result").path("video").path("file_id").asText();
    }

    private void writeField(ByteArrayOutputStream body, String boundary, String name, String value) throws IOException {
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    public byte[] downloadFile(String fileId) throws IOException, InterruptedException {
        String token = appProperties.getBotToken();

        HttpRequest getFileRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/bot" + token + "/getFile?file_id=" + fileId))
                .GET()
                .build();
        HttpResponse<String> getFileResponse = httpClient.send(getFileRequest, HttpResponse.BodyHandlers.ofString());

        JsonNode root = objectMapper.readTree(getFileResponse.body());
        if (!root.path("ok").asBoolean(false)) {
            throw new IOException("Telegram getFile failed: " + getFileResponse.body());
        }
        String filePath = root.path("result").path("file_path").asText();

        HttpRequest downloadRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.telegram.org/file/bot" + token + "/" + filePath))
                .GET()
                .build();
        HttpResponse<byte[]> downloadResponse = httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofByteArray());
        return downloadResponse.body();
    }
}
