package ru.gamebot.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Каталог кейсов CS2 для магазина наград (cs2_cases.json). Из него же сидер создаёт товары (Cs2CaseSeeder),
 * а карусель бота берёт названия, составы и картинки. Включается переменной CS2_CASES_ENABLED=true:
 * пока она выключена, товары неактивны и каталог (без покупки) виден только админам.
 */
@Slf4j
@Component
public class Cs2CaseCatalog {

    public static final String GROUP = "cs2_case";
    public static final String CATEGORY = "CS2 кейсы";
    private static final String TITLE_PREFIX = "CS2 кейс - ";
    public record CaseDef(String key, String name, double costRub, boolean withKey, List<String> skins) {
        public String title() { return TITLE_PREFIX + name; }

        /** Название для игрока: Кейс «Революция». */
        public String displayName() { return "Кейс «" + name + "»"; }

        /** Закупка x 1.18 (наценка донатов) x 100 x 1.2 (запас), вверх до сотен - как у игровой валюты. */
        public long priceExc() {
            long raw = BigDecimal.valueOf(costRub).multiply(BigDecimal.valueOf(141.6)).setScale(0, RoundingMode.CEILING).longValueExact();
            return (raw + 99) / 100 * 100;
        }

        /** Путь картинки в ресурсах бота; файла может не быть - карточка тогда без картинки. */
        public String imageResource() { return "cases/" + key + ".jpg"; }
    }

    private final List<CaseDef> cases;
    private final boolean enabled;

    public Cs2CaseCatalog(ObjectMapper objectMapper, @Value("${app.cs2-cases-enabled:false}") boolean enabled) throws IOException {
        this.enabled = enabled;
        List<CaseDef> loaded = new ArrayList<>();
        try (InputStream is = getClass().getResourceAsStream("/cs2_cases.json")) {
            if (is == null) throw new IOException("cs2_cases.json not found");
            JsonNode root = objectMapper.readTree(is);
            for (JsonNode node : root.path("cases")) {
                List<String> skins = new ArrayList<>();
                node.path("skins").forEach(t -> skins.add(t.asText()));
                loaded.add(new CaseDef(node.path("key").asText(), node.path("name").asText(), node.path("costRub").asDouble(),
                        node.path("withKey").asBoolean(false), List.copyOf(skins)));
            }
        }
        this.cases = List.copyOf(loaded);
        log.info("[Cs2CaseCatalog] loaded {} cases, enabled={}", cases.size(), enabled);
    }

    public boolean isEnabled() { return enabled; }

    public List<CaseDef> all() { return cases; }
}
