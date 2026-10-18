package ru.gamebot.platform.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.config.AppProperties;
import ru.gamebot.platform.domain.model.AppUser;

/** Ссылка на личный чат с менеджером поддержки (2026-10-18, решение владельца: обращение в поддержку заканчивается личным диалогом в Telegram, а не перепиской
 *  через бота). Менеджер задаётся APP_SUPPORT_USERNAME (тот же аккаунт, что и для оплаты заявок на донат). В поле ввода подставляется текст с ником и ID игрока:
 *  в личном чате менеджер иначе не увидит, кто это. Telegram сам ничего не отправляет, игрок описывает вопрос и жмёт «Отправить». */
@Service
@RequiredArgsConstructor
public class SupportManagerLinkService {

    private final AppProperties appProperties;

    public String linkFor(AppUser user) {
        String nick = user.getNickname() == null || user.getNickname().isBlank() ? "без ника" : user.getNickname();
        String text = "Здравствуйте! Пишу из бота EGC. Ник: " + nick + ", ID: " + user.getTelegramId() + ". Мой вопрос: ";
        // URLEncoder кодирует пробел как '+', Telegram его не разворачивает обратно: заменяем на %20 вручную
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
        return "https://t.me/" + appProperties.getSupportUsername().replace("@", "") + "?text=" + encoded;
    }
}
