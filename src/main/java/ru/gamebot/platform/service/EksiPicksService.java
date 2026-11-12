package ru.gamebot.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AppUser;

/** Раздел «🤝 Экси рекомендует» в Квестах: партнёрские рекомендации БЕЗ наград EXC (мотивированный трафик у рекламодателей запрещён).
 *  Список лежит в ресурсе eksi_picks.json. Подборка без ссылки (url пустой) или с "disabled": true игрокам не показывается,
 *  а когда показывать нечего - не показывается и сама кнопка в «Квестах». */
@Slf4j
@Service
@RequiredArgsConstructor
public class EksiPicksService {

    private static final String RESOURCE = "/eksi_picks.json";

    private final ObjectMapper objectMapper;

    /** countries - канонические названия стран (CountryNormalizer), где оффер доступен; пусто - без ограничения.
     *  adLabel - строка маркировки рекламы («Реклама. Рекламодатель, erid: ...»). */
    public record Pick(String key, String title, String description, String buttonLabel, String url, String adLabel,
                       Set<String> countries) {}

    /** Все готовые к показу подборки (с ссылкой, не отключённые). Файл маленький, читаем при каждом обращении - правка JSON подхватывается при деплое. */
    public List<Pick> all() {
        List<Pick> result = new ArrayList<>();
        try (InputStream is = getClass().getResourceAsStream(RESOURCE)) {
            if (is == null) return result;
            JsonNode root = objectMapper.readTree(is);
            for (JsonNode n : root) {
                if (n.path("disabled").asBoolean(false)) continue;
                String url = n.path("url").asText("").trim();
                String key = n.path("key").asText("").trim();
                if (url.isEmpty() || key.isEmpty()) continue;
                Set<String> countries = new HashSet<>();
                n.path("countries").forEach(c -> {
                    String canon = CountryNormalizer.canonical(c.asText(""));
                    if (canon != null) countries.add(canon);
                });
                result.add(new Pick(key, n.path("title").asText(""), n.path("description").asText(""),
                        n.path("buttonLabel").asText("🔗 Открыть"), url, n.path("adLabel").asText(""), countries));
            }
        } catch (Exception e) {
            log.warn("[EksiPicks] failed to read {}: {}", RESOURCE, e.getMessage());
        }
        return result;
    }

    /** Подборки, доступные игроку: страна не указана - показываем; указана и не из списка оффера - прячем. */
    public List<Pick> forUser(AppUser user) {
        String country = CountryNormalizer.canonical(user.getCountry());
        return all().stream()
                .filter(p -> p.countries().isEmpty() || country == null || p.countries().contains(country))
                .toList();
    }

    public Optional<Pick> find(AppUser user, String key) {
        return forUser(user).stream().filter(p -> p.key().equals(key)).findFirst();
    }
}
