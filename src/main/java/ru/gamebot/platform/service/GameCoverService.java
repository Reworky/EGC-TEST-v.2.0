package ru.gamebot.platform.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Обложки игр 16:9 (1280x720), лежащие в ресурсах проекта (game-covers/). Для этих игр они показываются в боте и мини-аппе
 *  вместо фото, загруженного через админку: так картинка ровно встаёт в карточки, и её не нужно заливать руками. */
@Slf4j
@Service
public class GameCoverService {

    /** Название игры в нижнем регистре -> файл в ресурсах. */
    private static final Map<String, String> RESOURCES = Map.of(
            "brawl stars", "game-covers/brawl-stars.jpg",
            "clash of clans", "game-covers/clash-of-clans.jpg",
            "clash royale", "game-covers/clash-royale.jpg",
            "cs2", "game-covers/cs2.jpg",
            "pubg mobile", "game-covers/pubg-mobile.jpg",
            "pubg pc", "game-covers/pubg-pc.jpg");

    private final Map<String, byte[]> bytesCache = new ConcurrentHashMap<>();

    /** Путь к файлу обложки в ресурсах, если для игры она есть. */
    public Optional<String> resourceFor(String gameName) {
        if (gameName == null) return Optional.empty();
        return Optional.ofNullable(RESOURCES.get(gameName.trim().toLowerCase()));
    }

    /** Байты обложки игры из ресурсов; empty, если для игры своей обложки нет или файл не читается. */
    public Optional<byte[]> bytesFor(String gameName) {
        Optional<String> resource = resourceFor(gameName);
        if (resource.isEmpty()) return Optional.empty();
        byte[] cached = bytesCache.get(resource.get());
        if (cached != null) return Optional.of(cached);
        try (InputStream in = getClass().getResourceAsStream("/" + resource.get())) {
            if (in == null) return Optional.empty();
            byte[] bytes = in.readAllBytes();
            bytesCache.put(resource.get(), bytes);
            return Optional.of(bytes);
        } catch (IOException e) {
            log.warn("Failed to read game cover {}", resource.get(), e);
            return Optional.empty();
        }
    }
}
