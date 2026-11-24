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
 * Каталог скинов CS2 для магазина наград (cs2_skins.json, 24.11.2026): по образцу кейсов (Cs2CaseCatalog). Из него сидер создаёт товары
 * (Cs2SkinSeeder), карусель бота берёт названия и картинки, автовыдача (Cs2CaseAutoIssueService) покупает предмет на market.csgo.com
 * по hashName. Обмен включается той же переменной, что и кейсы: CS2_CASES_ENABLED=true.
 */
@Slf4j
@Component
public class Cs2SkinCatalog {

    public static final String GROUP = "cs2_skin";
    public static final String CATEGORY = "CS2 скины";
    private static final String TITLE_PREFIX = "CS2 скин - ";

    /** hashName - market_hash_name со степенью износа; wear - износ по-английски, как в hashName. */
    public record SkinDef(String key, String name, String hashName, String wear, double costRub) {
        public String title() { return TITLE_PREFIX + name + " (" + wear + ")"; }

        /** Название для игрока: AK-47 | Wintergreen (после полевых испытаний). */
        public String displayName() { return name + " (" + wearRu() + ")"; }

        public String wearRu() {
            return switch (wear) {
                case "Factory New" -> "прямо с завода";
                case "Minimal Wear" -> "немного поношенное";
                case "Field-Tested" -> "после полевых испытаний";
                case "Well-Worn" -> "поношенное";
                case "Battle-Scarred" -> "закалённое в боях";
                default -> wear;
            };
        }

        /** Закупка x 141.6 (как у кейсов и игровой валюты), вверх до сотен. */
        public long priceExc() {
            long raw = BigDecimal.valueOf(costRub).multiply(BigDecimal.valueOf(141.6)).setScale(0, RoundingMode.CEILING).longValueExact();
            return (raw + 99) / 100 * 100;
        }

        public String imageResource() { return "skins/" + key + ".jpg"; }

        /** Тип оружия для выбора категории в боте (по названию оружия до « | »): pistol, rifle, sniper, smg, shotgun, lmg. */
        public String typeKey() {
            String weapon = name.contains(" | ") ? name.substring(0, name.indexOf(" | ")) : name;
            return switch (weapon) {
                case "Glock-18", "USP-S", "P2000", "Desert Eagle", "P250", "Five-SeveN", "Tec-9", "CZ75-Auto", "Dual Berettas", "R8 Revolver" -> "pistol";
                case "AK-47", "M4A4", "M4A1-S", "Galil AR", "FAMAS", "SG 553", "AUG" -> "rifle";
                case "AWP", "SSG 08", "SCAR-20", "G3SG1" -> "sniper";
                case "MAC-10", "MP9", "MP7", "MP5-SD", "UMP-45", "P90", "PP-Bizon" -> "smg";
                case "Nova", "XM1014", "MAG-7", "Sawed-Off" -> "shotgun";
                case "Negev", "M249" -> "lmg";
                default -> "other";
            };
        }
    }

    /** Типы оружия в порядке показа и подписи (как в фильтре маркетплейса); показываются только непустые. */
    public static final java.util.Map<String, String> TYPE_LABELS = new java.util.LinkedHashMap<>();
    static {
        TYPE_LABELS.put("pistol", "Пистолеты");
        TYPE_LABELS.put("rifle", "Винтовки");
        TYPE_LABELS.put("sniper", "Снайперские винтовки");
        TYPE_LABELS.put("smg", "Пистолеты-пулемёты");
        TYPE_LABELS.put("lmg", "Пулемёты");
        TYPE_LABELS.put("shotgun", "Дробовики");
    }

    /** Скины типа (ключ из TYPE_LABELS); «all» - все. Порядок каталога (от дешёвых к дорогим) сохраняется. */
    public List<SkinDef> byType(String typeKey) {
        if (typeKey == null || "all".equals(typeKey) || !TYPE_LABELS.containsKey(typeKey)) return skins;
        return skins.stream().filter(sd -> sd.typeKey().equals(typeKey)).toList();
    }

    private final List<SkinDef> skins;
    private final boolean enabled;

    public Cs2SkinCatalog(ObjectMapper objectMapper, @Value("${app.cs2-cases-enabled:false}") boolean enabled) throws IOException {
        this.enabled = enabled;
        List<SkinDef> loaded = new ArrayList<>();
        try (InputStream is = getClass().getResourceAsStream("/cs2_skins.json")) {
            if (is == null) throw new IOException("cs2_skins.json not found");
            JsonNode root = objectMapper.readTree(is);
            for (JsonNode node : root.path("skins")) {
                loaded.add(new SkinDef(node.path("key").asText(), node.path("name").asText(), node.path("hashName").asText(),
                        node.path("wear").asText(), node.path("costRub").asDouble()));
            }
        }
        this.skins = List.copyOf(loaded);
        log.info("[Cs2SkinCatalog] loaded {} skins, enabled={}", skins.size(), enabled);
    }

    public boolean isEnabled() { return enabled; }

    public List<SkinDef> all() { return skins; }
}
