package ru.gamebot.platform.service;

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
        // тот же вид, что у остальных кнопок к менеджеру (ManagerMessage): откуда пришёл и кто это; вопрос игрок дописывает сам в конце
        String text = ManagerMessage.build(user, "Пишу в поддержку", "бот → Помощь → Поддержка", null, null) + "\n\nМой вопрос: ";
        return ManagerMessage.link(appProperties.getSupportUsername(), text);
    }
}
