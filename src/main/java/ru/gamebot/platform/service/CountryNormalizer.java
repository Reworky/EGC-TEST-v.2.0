package ru.gamebot.platform.service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Приведение свободного ввода страны («Украина» / «Україна» / «ukraine») к одному названию для агрегации гео-данных.
 *  Известные написания сводятся к русскому названию; неизвестные остаются как ввёл игрок (без лишних пробелов, с заглавной буквы). */
public final class CountryNormalizer {

    private static final Map<String, String> ALIASES = new HashMap<>();

    private static void add(String canonical, String... variants) {
        ALIASES.put(key(canonical), canonical);
        for (String v : variants) ALIASES.put(key(v), canonical);
    }

    static {
        add("Россия", "russia", "рф", "российская федерация", "russian federation", "росія", "россия рф", "расея");
        add("Украина", "україна", "ukraine", "украіна", "ua", "укр");
        add("Беларусь", "белоруссия", "білорусь", "belarus", "республика беларусь", "рб", "беларус");
        add("Казахстан", "қазақстан", "kazakhstan", "kz");
        add("Узбекистан", "ўзбекистон", "uzbekistan", "uz");
        add("Кыргызстан", "киргизия", "kyrgyzstan", "кыргызстан");
        add("Таджикистан", "tajikistan");
        add("Армения", "հայաստան", "armenia");
        add("Азербайджан", "azərbaycan", "azerbaijan");
        add("Грузия", "საქართველო", "georgia");
        add("Молдова", "молдавия", "moldova");
        add("Латвия", "latvia", "latvija");
        add("Литва", "lithuania", "lietuva");
        add("Эстония", "estonia", "eesti");
        add("Германия", "deutschland", "germany");
        add("Польша", "polska", "poland");
        add("Турция", "türkiye", "turkey", "turkiye");
        add("Израиль", "israel");
        add("США", "usa", "united states", "us", "америка", "сша usa");
        add("Канада", "canada");
        add("Великобритания", "uk", "united kingdom", "англия", "england");
    }

    private CountryNormalizer() {
    }

    private static String key(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[\\p{Punct}\\s]+", " ").trim();
    }

    /** Каноническое название страны для показа и группировки. */
    public static String canonical(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim().replaceAll("\\s+", " ");
        if (trimmed.isEmpty()) return null;
        String hit = ALIASES.get(key(trimmed));
        if (hit != null) return hit;
        return trimmed.substring(0, 1).toUpperCase(Locale.ROOT) + trimmed.substring(1);
    }
}
