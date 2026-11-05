package ru.gamebot.platform.service;

import java.util.List;
import java.util.Optional;

/** Биржи-партнёры для вывода в крипте сверх порога 10 000 EXC (05.11.2026): игрок регистрируется по нашей реферальной ссылке и присылает UID, модератор сверяет UID в реферальном кабинете биржи.
 *  Новая биржа: константа здесь и в ALL (ссылка и формат UID); в боте ничего больше менять не нужно. */
public final class CryptoExchanges {

    /** key - код в callback'ах (латиница без двоеточий), uidRegex - формат UID, который принимает бот. */
    public record Exchange(String key, String title, String refLink, String uidRegex, String uidHint) {}

    public static final Exchange PIONEX = new Exchange("pionex", "Pionex", "https://www.pionex.com/signUp?r=experiencegamingclub", "\\d{8}", "8 цифр");
    public static final Exchange BYBIT = new Exchange("bybit", "Bybit",
            "https://www.bybit.com/invite?ref=WMG76X&medium=referral&utm_campaign=evergreen&share_to=post", "\\d{8}", "8 цифр");

    public static final List<Exchange> ALL = List.of(PIONEX, BYBIT);

    private CryptoExchanges() {}

    public static Optional<Exchange> byKey(String key) {
        return ALL.stream().filter(e -> e.key().equals(key)).findFirst();
    }
}
