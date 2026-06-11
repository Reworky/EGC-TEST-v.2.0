package ru.gamebot.platform.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.config.AppProperties;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final AppProperties appProperties;

    public boolean isAdmin(Long telegramId) {
        return appProperties.getAdminIds().contains(telegramId);
    }

    public boolean isModerator(Long telegramId) {
        return isAdmin(telegramId) || appProperties.getModeratorIds().contains(telegramId);
    }
}
