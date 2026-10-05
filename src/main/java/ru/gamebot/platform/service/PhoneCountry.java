package ru.gamebot.platform.service;

import java.util.HashMap;
import java.util.Map;

/** Страна по международному коду номера телефона (+380 -> «Украина»). Названия те же, что в {@link CountryNormalizer},
 *  чтобы гео-статистика не раздваивалась. Для неизвестного кода возвращает null. */
public final class PhoneCountry {

    private static final Map<String, String> BY_CODE = new HashMap<>();

    private static void add(String country, String... codes) {
        for (String c : codes) BY_CODE.put(c, country);
    }

    static {
        add("Украина", "380");
        add("Беларусь", "375");
        add("Армения", "374");
        add("Молдова", "373");
        add("Латвия", "371");
        add("Литва", "370");
        add("Эстония", "372");
        add("Азербайджан", "994");
        add("Грузия", "995");
        add("Узбекистан", "998");
        add("Кыргызстан", "996");
        add("Таджикистан", "992");
        add("Туркменистан", "993");
        add("Монголия", "976");
        add("Германия", "49");
        add("Польша", "48");
        add("Турция", "90");
        add("Израиль", "972");
        add("США", "1");
        add("Великобритания", "44");
        add("Франция", "33");
        add("Испания", "34");
        add("Италия", "39");
        add("Нидерланды", "31");
        add("Бельгия", "32");
        add("Чехия", "420");
        add("Словакия", "421");
        add("Болгария", "359");
        add("Румыния", "40");
        add("Сербия", "381");
        add("Черногория", "382");
        add("Греция", "30");
        add("Португалия", "351");
        add("Финляндия", "358");
        add("Швеция", "46");
        add("Норвегия", "47");
        add("Дания", "45");
        add("Швейцария", "41");
        add("Австрия", "43");
        add("Китай", "86");
        add("Индия", "91");
        add("Япония", "81");
        add("Южная Корея", "82");
        add("Вьетнам", "84");
        add("Таиланд", "66");
        add("ОАЭ", "971");
        add("Египет", "20");
        add("Бразилия", "55");
        add("Мексика", "52");
        add("Аргентина", "54");
        add("Австралия", "61");
        add("Индонезия", "62");
        add("Филиппины", "63");
        add("Сингапур", "65");
    }

    private PhoneCountry() {
    }

    /** Страна по номеру в любом виде («+380…», «380…», «+7 (999)…»). Null, если код не распознан. */
    public static String fromPhone(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 8) return null;
        // +7: коды 6xx и 7xx — Казахстан, остальное — Россия. Номера, набранные через 8, считаем российскими.
        if (digits.startsWith("7")) {
            char operator = digits.length() > 1 ? digits.charAt(1) : '0';
            return (operator == '6' || operator == '7') ? "Казахстан" : "Россия";
        }
        if (digits.startsWith("8") && digits.length() == 11) return "Россия";
        for (int len = 3; len >= 1; len--) {
            if (digits.length() <= len) continue;
            String country = BY_CODE.get(digits.substring(0, len));
            if (country != null) return country;
        }
        return null;
    }
}
