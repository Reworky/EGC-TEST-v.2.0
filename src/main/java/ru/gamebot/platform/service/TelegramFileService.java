package ru.gamebot.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.config.AppProperties;

@Service
@RequiredArgsConstructor
public class TelegramFileService {

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

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
