package ru.gamebot.platform.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ru.gamebot.platform.service.ChannelContentService;

/**
 * Очередь ручных постов канала (21.10.2026, решение владельца): все заготовленные посты из контент-плана (рубрики, опросы,
 * интерактивы, Хэллоуин) лежат в ресурсе channel_manual_posts.json и ставятся в автопостинг: админам приходит обычная карточка
 * ✅/✏️/❌ в назначенный час (поле at, UTC; сетка владельца 12:00/16:00/20:00 МСК+4 = 05/09/13 UTC), без наслоения на автопосты.
 *
 * Идемпотентно: пост с тем же key повторно не создаётся (даже опубликованный или отклонённый), поэтому сидер безопасно выполнять на
 * каждом старте. Бэклог: если час уже прошёл меньше чем 48 часов назад (деплой позже срока), пост не теряется, а уходит карточкой
 * через 15 минут, следующие просроченные - с шагом 4 часа; старше 48 часов - пропускается с записью в лог.
 * Снять пост с плана: оставить запись в JSON и добавить "disabled": true (удаление записи не отменит уже созданный черновик).
 */
@Slf4j
@Component
@Order(9)
@RequiredArgsConstructor
public class ChannelManualPostSeeder implements CommandLineRunner {

    private static final String RESOURCE = "/channel_manual_posts.json";
    private static final Duration MAX_OVERDUE = Duration.ofHours(48);

    private final ChannelContentService channelContentService;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) {
        try (InputStream is = getClass().getResourceAsStream(RESOURCE)) {
            if (is == null) {
                log.warn("[ManualPosts] resource {} not found", RESOURCE);
                return;
            }
            JsonNode root = objectMapper.readTree(is);
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime overdueSlot = now.plusMinutes(15);
            int created = 0;
            for (JsonNode n : root) {
                String key = n.path("key").asText("");
                String text = n.path("text").asText("");
                if (key.isBlank() || text.isBlank()) continue;
                // "disabled": true - пост снят с плана: новый не создаём, а ещё не отправленную админам карточку отклоняем
                if (n.path("disabled").asBoolean(false)) {
                    int cancelled = channelContentService.cancelManualPost(key);
                    if (cancelled > 0) log.info("[ManualPosts] {} disabled: cancelled {} pending draft(s)", key, cancelled);
                    continue;
                }
                try {
                    LocalDateTime at = LocalDateTime.parse(n.path("at").asText());
                    if (at.isBefore(now)) {
                        if (Duration.between(at, now).compareTo(MAX_OVERDUE) > 0) {
                            log.info("[ManualPosts] {} skipped: slot {} UTC is more than 48h in the past", key, at);
                            continue;
                        }
                        at = overdueSlot;
                        overdueSlot = overdueSlot.plusHours(4);
                    }
                    if (channelContentService.queueManualPost(key, n.path("rubric").asText(""), n.path("note").asText(""), text, at)) {
                        created++;
                    }
                } catch (Exception e) {
                    log.error("[ManualPosts] failed to queue {}", key, e);
                }
            }
            if (created > 0) log.info("[ManualPosts] queued {} posts", created);
        } catch (Exception e) {
            log.error("[ManualPosts] seeder failed", e);
        }
    }
}
