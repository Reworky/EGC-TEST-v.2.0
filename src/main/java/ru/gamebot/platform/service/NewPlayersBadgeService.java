package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.domain.model.AppSetting;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.AppUserRepository;

/** Счётчик «новых игроков» на кнопках админки (17.11.2026, просьба владельца): число пришедших в бота
 *  с момента, когда админ последний раз открыл экран «🆕 Новые игроки». Как и «🆕 Регистрации», счётчик общий
 *  для всех админов: открыл экран - уведомление сброшено. Момент просмотра хранится в настройке {@value #KEY}.
 *  Если настройки ещё нет (первый запуск после деплоя), считаем за последние сутки, чтобы не показать весь бэклог базы. */
@Service
@RequiredArgsConstructor
public class NewPlayersBadgeService {

    public static final String KEY = "admin.new_players.seen_at";

    private final AppSettingRepository settingRepository;
    private final AppUserRepository userRepository;

    public LocalDateTime seenAt() {
        try {
            return settingRepository.findById(KEY)
                    .map(s -> LocalDateTime.parse(s.getValue().trim()))
                    .orElse(LocalDateTime.now().minusDays(1));
        } catch (Exception e) {
            return LocalDateTime.now().minusDays(1);
        }
    }

    /** Сколько игроков пришло после последнего просмотра. Не должно ронять отрисовку меню. */
    public long unseenCount() {
        try {
            return userRepository.countByCreatedAtAfter(seenAt());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Админ открыл экран: сбрасываем счётчик. */
    public void markSeen() {
        try {
            AppSetting s = settingRepository.findById(KEY).orElseGet(() -> {
                AppSetting created = new AppSetting();
                created.setKey(KEY);
                return created;
            });
            s.setValue(LocalDateTime.now().toString());
            settingRepository.save(s);
        } catch (Exception ignored) {
            // счётчик - вспомогательная функция, экран важнее
        }
    }
}
