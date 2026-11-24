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
 * Каталог капсул со стикерами CS2 для магазина наград (cs2_capsules.json, 24.11.2026): отдельный раздел рядом с кейсами и скинами.
 * Из него сидер создаёт товары (Cs2CapsuleSeeder), карусель бота берёт названия и картинки, автовыдача (Cs2CaseAutoIssueService)
 * покупает капсулу на market.csgo.com по hashName. Включается той же переменной, что и кейсы: CS2_CASES_ENABLED=true.
 */
@Slf4j
@Component
public class Cs2CapsuleCatalog {

    public static final String GROUP = "cs2_capsule";
    public static final String CATEGORY = "CS2 капсулы";
    private static final String TITLE_PREFIX = "CS2 капсула - ";

    public record CapsuleDef(String key, String name, String hashName, double costRub) {
        public String title() { return TITLE_PREFIX + name; }

        /** Закупка x 141.6 (как у кейсов и скинов), вверх до сотен. */
        public long priceExc() {
            long raw = BigDecimal.valueOf(costRub).multiply(BigDecimal.valueOf(141.6)).setScale(0, RoundingMode.CEILING).longValueExact();
            return (raw + 99) / 100 * 100;
        }

        public String imageResource() { return "capsules/" + key + ".jpg"; }
    }

    private final List<CapsuleDef> capsules;
    private final boolean enabled;

    public Cs2CapsuleCatalog(ObjectMapper objectMapper, @Value("${app.cs2-cases-enabled:false}") boolean enabled) throws IOException {
        this.enabled = enabled;
        List<CapsuleDef> loaded = new ArrayList<>();
        try (InputStream is = getClass().getResourceAsStream("/cs2_capsules.json")) {
            if (is == null) throw new IOException("cs2_capsules.json not found");
            JsonNode root = objectMapper.readTree(is);
            for (JsonNode node : root.path("capsules")) {
                loaded.add(new CapsuleDef(node.path("key").asText(), node.path("name").asText(), node.path("hashName").asText(), node.path("costRub").asDouble()));
            }
        }
        this.capsules = List.copyOf(loaded);
        log.info("[Cs2CapsuleCatalog] loaded {} capsules, enabled={}", capsules.size(), enabled);
    }

    public boolean isEnabled() { return enabled; }

    public List<CapsuleDef> all() { return capsules; }
}
