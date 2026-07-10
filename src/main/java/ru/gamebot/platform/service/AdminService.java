package ru.gamebot.platform.service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.gamebot.platform.config.AppProperties;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final AppProperties appProperties;
    private final AppUserRepository appUserRepository;

    public boolean isAdmin(Long telegramId) {
        return resolvedAdminIds().contains(telegramId)
                || appUserRepository.findByTelegramId(telegramId)
                .map(user -> "ADMIN".equalsIgnoreCase(user.getStaffRole()))
                .orElse(false);
    }

    public boolean isModerator(Long telegramId) {
        return isAdmin(telegramId)
                || resolvedModeratorIds().contains(telegramId)
                || appUserRepository.findByTelegramId(telegramId)
                .map(user -> "MODER".equalsIgnoreCase(user.getStaffRole()))
                .orElse(false);
    }

    public String configuredRole(Long telegramId) {
        if (resolvedAdminIds().contains(telegramId)) {
            return "ADMIN";
        }
        if (resolvedModeratorIds().contains(telegramId)) {
            return "MODER";
        }
        return "USER";
    }

    public String effectiveRole(AppUser user) {
        String configured = configuredRole(user.getTelegramId());
        if ("ADMIN".equals(configured)) {
            return "ADMIN";
        }
        if ("MODER".equals(configured)) {
            return "MODER";
        }
        if ("ADMIN".equalsIgnoreCase(user.getStaffRole())) {
            return "ADMIN";
        }
        if ("MODER".equalsIgnoreCase(user.getStaffRole())) {
            return "MODER";
        }
        return "USER";
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

    public Set<Long> allAdminIds() {
        Set<Long> ids = new HashSet<>(resolvedAdminIds());
        ids.addAll(appUserRepository.findAll().stream()
                .filter(user -> "ADMIN".equalsIgnoreCase(user.getStaffRole()))
                .map(AppUser::getTelegramId)
                .collect(Collectors.toSet()));
        return ids;
    }

    public Set<Long> allModeratorIds() {
        Set<Long> ids = new HashSet<>(resolvedModeratorIds());
        ids.addAll(appUserRepository.findAll().stream()
                .filter(user -> "ADMIN".equalsIgnoreCase(user.getStaffRole()) || "MODER".equalsIgnoreCase(user.getStaffRole()))
                .map(AppUser::getTelegramId)
                .collect(Collectors.toSet()));
        ids.addAll(allAdminIds());
        return ids;
    }

    /** Только модераторы, без админов — для уведомлений, которые не должны попадать к админу (например, поддержка). */
    public Set<Long> strictModeratorIds() {
        Set<Long> ids = new HashSet<>(resolvedModeratorIds());
        ids.addAll(appUserRepository.findAll().stream()
                .filter(user -> "MODER".equalsIgnoreCase(user.getStaffRole()))
                .map(AppUser::getTelegramId)
                .collect(Collectors.toSet()));
        ids.removeAll(allAdminIds());
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
