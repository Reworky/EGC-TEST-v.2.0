package ru.gamebot.platform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Каталог кейсов CS2 для магазина наград (cs2_cases.json). Из него же сидер создаёт товары (Cs2CaseSeeder),
 * а карусель бота берёт названия, серии, теги и описания. Включается переменной CS2_CASES_ENABLED=true:
 * пока она выключена, товары неактивны и каталог (без покупки) виден только админам.
 */
@Slf4j
@Component
public class Cs2CaseCatalog {

    public static final String GROUP = "cs2_case";
    public static final String CATEGORY = "CS2 кейсы";
    private static final String TITLE_PREFIX = "CS2 кейс - ";
    /** Сколько кейсов показывает фильтр «Доступные» (самые дешёвые). */
    private static final int CHEAP_LIMIT = 10;

    public record CaseDef(String key, String name, String series, int costRub, Set<String> tags, String blurb) {
        public String title() { return TITLE_PREFIX + name; }

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
                Set<String> tags = new LinkedHashSet<>();
                node.path("tags").forEach(t -> tags.add(t.asText()));
                loaded.add(new CaseDef(node.path("key").asText(), node.path("name").asText(), node.path("series").asText(),
                        node.path("costRub").asInt(), tags, node.path("blurb").asText()));
            }
        }
        this.cases = List.copyOf(loaded);
        log.info("[Cs2CaseCatalog] loaded {} cases, enabled={}", cases.size(), enabled);
    }

    public boolean isEnabled() { return enabled; }

    public List<CaseDef> all() { return cases; }

    /** Список для фильтра карусели: pop, new, cheap или all (любое другое значение = all). */
    public List<CaseDef> filter(String filter) {
        return switch (filter) {
            case "pop" -> cases.stream().filter(c -> c.tags().contains("popular")).toList();
            case "new" -> cases.stream().filter(c -> c.tags().contains("new")).toList();
            case "cheap" -> cases.stream().sorted(Comparator.comparingLong(CaseDef::priceExc)).limit(CHEAP_LIMIT).toList();
            default -> cases;
        };
    }
}
