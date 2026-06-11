package ru.gamebot.platform.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.config.AppProperties;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final AppProperties appProperties;

    public boolean isAdmin(Long telegramId) {
        return resolvedAdminIds().contains(telegramId);
    }

    public boolean isModerator(Long telegramId) {
        return isAdmin(telegramId) || resolvedModeratorIds().contains(telegramId);
    }

    public Set<Long> resolvedAdminIds() {
        Set<Long> ids = new HashSet<>(appProperties.getAdminIds());
        ids.addAll(parseIds(appProperties.getAdminIdsRaw()));
        ids.addAll(parseIds(appProperties.getInitialAdminId()));
        return ids;
    }

    public Set<Long> resolvedModeratorIds() {
        Set<Long> ids = new HashSet<>(appProperties.getModeratorIds());
        ids.addAll(parseIds(appProperties.getModeratorIdsRaw()));
        return ids;
    }

    private Set<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(this::parseLong)
                .filter(value -> value != null)
                .collect(java.util.stream.Collectors.toSet());
    }

    private Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
