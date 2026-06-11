package ru.gamebot.platform.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.bot.UserSession;

@Service
public class SessionService {

    private final Map<Long, UserSession> sessions = new ConcurrentHashMap<>();

    public UserSession get(Long telegramId) {
        return sessions.computeIfAbsent(telegramId, ignored -> new UserSession());
    }
}
