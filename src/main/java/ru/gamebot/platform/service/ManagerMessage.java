package ru.gamebot.platform.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import ru.gamebot.platform.domain.model.AppUser;

/**
 * Готовое сообщение менеджеру для кнопок «Написать менеджеру» (09.11.2026, владелец: менеджер должен сразу понимать, откуда пришёл человек и как с ним работать).
 * Кнопка открывает личный чат менеджера со вписанным текстом (t.me/<менеджер>?text=): менеджер видит его как обычное сообщение игрока и больше ничего о нём не знает,
 * поэтому в тексте всегда: о чём вопрос, откуда игрок пришёл в боте (путь по меню), что ему нужно, заказ (если есть) и кто он (ник, @username, ID).
 *
 * <p>Ссылка в кнопке ограничена по длине (~2 000 символов), а кириллица в ней раздувается примерно в 6 раз, поэтому при нехватке места по очереди отбрасываются
 * «Нужно» и «Заказ», а заголовок, «Откуда» и строка игрока остаются всегда.
 */
public final class ManagerMessage {

    private static final int MAX_ENCODED = 1750;

    private ManagerMessage() {
    }

    /** Игрок из базы: ник, @username, ID и баланс EXC. */
    public static String build(AppUser user, String headline, String where, String need, String facts) {
        return build(user, headline, where, need, facts, null);
    }

    /** То же, но с короткой ссылкой на заявку в боте (t.me/<бот>?start=rq_...): менеджер нажимает её прямо в личке и сразу попадает на карточку заявки. */
    public static String build(AppUser user, String headline, String where, String need, String facts, String requestLink) {
        String nick = user.getNickname() == null || user.getNickname().isBlank() ? "без ника" : user.getNickname();
        String tg = user.getTelegramUsername() != null && !user.getTelegramUsername().isBlank() ? " @" + user.getTelegramUsername() : "";
        String player = "Игрок: " + nick + tg + " · ID " + user.getTelegramId() + " · " + user.getCoins() + " EXC";
        return compose(player, headline, where, need, facts, requestLink);
    }

    /** Короткий вид без заказа и ссылки на заявку (партнёрские квесты банков): тот же единый шаблон. */
    public static String buildSimple(AppUser user, String headline, String need) {
        return build(user, headline, null, need, null, null);
    }

    /** Игрок без записи в базе EGC (например, пришёл только в VPN-бот): имя из Telegram и ID. */
    public static String buildForId(Long telegramId, String name, String headline, String where, String need, String facts) {
        return buildForId(telegramId, name, headline, where, need, facts, null);
    }

    public static String buildForId(Long telegramId, String name, String headline, String where, String need, String facts, String requestLink) {
        String player = "Игрок: " + (name == null || name.isBlank() ? "без имени" : name) + " · ID " + telegramId;
        return compose(player, headline, where, need, facts, requestLink);
    }

    /** Единый вид сообщения менеджеру (владелец, 10.11): приветствие и суть, пустая строка, при необходимости заказ и ссылка на заявку, пустая строка, строка игрока, пустая строка и просьба
     *  с большой буквы. Откуда игрок пришёл по меню (where) в текст больше не попадает: суть и просьба уже говорят, о чём вопрос. */
    private static String compose(String player, String headline, String where, String need, String facts, String requestLink) {
        String head = "Здравствуйте! " + headline + ".";
        StringBuilder details = new StringBuilder();
        if (facts != null && !facts.isBlank()) details.append(facts);
        if (requestLink != null && !requestLink.isBlank()) {
            if (details.length() > 0) details.append("\n");
            details.append("Заявка: ").append(requestLink);
        }
        String detailsBlock = details.length() == 0 ? "" : "\n\n" + details;
        String needBlock = need == null || need.isBlank() ? "" : "\n\n" + Character.toUpperCase(need.charAt(0)) + need.substring(1);
        List<String> variants = new ArrayList<>();
        variants.add(head + detailsBlock + "\n\n" + player + needBlock);
        variants.add(head + detailsBlock + "\n\n" + player);
        for (String v : variants) {
            if (encodedLength(v) <= MAX_ENCODED) return v;
        }
        String refOnly = requestLink == null || requestLink.isBlank() ? "" : "\n\nЗаявка: " + requestLink;
        return head + refOnly + "\n\n" + player;
    }

    /** Ссылка на чат менеджера с готовым текстом; пробелы кодируем как %20 (Telegram не разворачивает «+» обратно). */
    public static String link(String managerUsername, String text) {
        String name = managerUsername == null ? "" : managerUsername.replace("@", "");
        return "https://t.me/" + name + "?text=" + encode(text);
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static int encodedLength(String text) {
        return encode(text).length();
    }
}
