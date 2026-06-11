package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.User;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;

@Service
@RequiredArgsConstructor
public class UserService {

    private final AppUserRepository appUserRepository;

    @Transactional
    public AppUser getOrCreate(User telegramUser, Long referredByTelegramId) {
        Optional<AppUser> existing = appUserRepository.findByTelegramId(telegramUser.getId());
        if (existing.isPresent()) {
            AppUser user = existing.get();
            updateTelegramProfile(user, telegramUser);
            return appUserRepository.save(user);
        }

        AppUser user = new AppUser();
        user.setTelegramId(telegramUser.getId());
        user.setReferredByTelegramId(resolveReferral(telegramUser.getId(), referredByTelegramId));
        user.setXp(0);
        user.setWeeklyXp(0);
        user.setCoins(0);
        user.setCompletedQuests(0);
        user.setInvitedFriends(0);
        user.setStreakDays(0);
        user.setCreatedAt(LocalDateTime.now());
        updateTelegramProfile(user, telegramUser);
        return appUserRepository.save(user);
    }

    @Transactional
    public AppUser save(AppUser user) {
        return appUserRepository.save(user);
    }

    public Optional<AppUser> findByTelegramId(Long telegramId) {
        return appUserRepository.findByTelegramId(telegramId);
    }

    public long getOverallRank(AppUser user) {
        List<AppUser> sorted = appUserRepository.findAllByRegistrationCompletedTrueOrderByXpDescTelegramIdAsc();
        return getRank(sorted, user.getTelegramId());
    }

    public long getWeeklyRank(AppUser user) {
        List<AppUser> sorted = appUserRepository.findAllByRegistrationCompletedTrueOrderByWeeklyXpDescTelegramIdAsc();
        return getRank(sorted, user.getTelegramId());
    }

    private long getRank(List<AppUser> sorted, Long telegramId) {
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).getTelegramId().equals(telegramId)) {
                return i + 1L;
            }
        }
        return sorted.size() + 1L;
    }

    public String getLevelName(long xp) {
        if (xp >= 6000) {
            return "Элита";
        }
        if (xp >= 3000) {
            return "Мастер";
        }
        if (xp >= 1500) {
            return "Ветеран";
        }
        if (xp >= 700) {
            return "Профи";
        }
        if (xp >= 300) {
            return "Опытный";
        }
        if (xp >= 100) {
            return "Игрок";
        }
        return "Новичок";
    }

    public List<String> getAchievements(AppUser user) {
        return List.of(
                user.getCompletedQuests() >= 1 ? "🏅 Первое задание" : null,
                user.getCompletedQuests() >= 10 ? "🔥 10 заданий" : null,
                user.getCompletedQuests() >= 100 ? "👑 100 заданий" : null,
                user.getInvitedFriends() >= 1 ? "🤝 Первый реферал" : null,
                user.getInvitedFriends() >= 10 ? "🚀 10 рефералов" : null,
                user.getXp() >= 6000 ? "🌟 Легенда клуба" : null
        ).stream().filter(item -> item != null).toList();
    }

    @Transactional
    public AppUser completeRegistration(AppUser user, String nickname, Integer age, String country,
                                        List<String> platforms, List<String> interests) {
        user.setNickname(nickname);
        user.setAge(age);
        user.setCountry(country);
        user.setPlatformsCsv(String.join(", ", platforms));
        user.setInterestsCsv(interests.isEmpty() ? "Не выбраны" : String.join(", ", interests));
        user.setRegistrationCompleted(true);
        return appUserRepository.save(user);
    }

    public List<AppUser> topOverall() {
        return appUserRepository.findTop20ByRegistrationCompletedTrueOrderByXpDescTelegramIdAsc();
    }

    public List<AppUser> topWeekly() {
        return appUserRepository.findTop20ByRegistrationCompletedTrueOrderByWeeklyXpDescTelegramIdAsc();
    }

    @Transactional
    public String registerActivity(AppUser user) {
        LocalDate today = LocalDate.now();
        LocalDate lastDate = user.getLastActivityDate();
        if (lastDate != null && lastDate.equals(today)) {
            return null;
        }

        if (lastDate != null && lastDate.plusDays(1).equals(today)) {
            user.setStreakDays(user.getStreakDays() + 1);
        } else {
            user.setStreakDays(1);
        }
        user.setLastActivityDate(today);

        long streakBonus = 0;
        if (user.getStreakDays() == 7) {
            streakBonus = 20;
        } else if (user.getStreakDays() == 30) {
            streakBonus = 100;
        } else if (user.getStreakDays() == 90) {
            streakBonus = 500;
        }

        if (streakBonus > 0) {
            user.setXp(user.getXp() + streakBonus);
            user.setWeeklyXp(user.getWeeklyXp() + streakBonus);
        }

        appUserRepository.save(user);
        if (streakBonus > 0) {
            return "🔥 Серия входов: " + user.getStreakDays() + " дней подряд.\n"
                    + "🎁 Бонус за активность: +" + streakBonus + " XP.";
        }
        return "📅 Серия входов обновлена: " + user.getStreakDays() + " дней подряд.";
    }

    @Transactional
    public void grantReferralReward(AppUser invitedUser) {
        if (invitedUser.isReferralRewardProcessed()) {
            return;
        }
        Long referrerTelegramId = invitedUser.getReferredByTelegramId();
        if (referrerTelegramId == null) {
            return;
        }
        AppUser referrer = appUserRepository.findByTelegramId(referrerTelegramId).orElse(null);
        if (referrer == null) {
            return;
        }
        referrer.setInvitedFriends(referrer.getInvitedFriends() + 1);
        referrer.setXp(referrer.getXp() + 30);
        referrer.setWeeklyXp(referrer.getWeeklyXp() + 30);
        referrer.setCoins(referrer.getCoins() + 50);
        invitedUser.setReferralRewardProcessed(true);
        appUserRepository.save(referrer);
        appUserRepository.save(invitedUser);
    }

    @Transactional
    public void addReward(AppUser user, long xp, long coins) {
        user.setXp(user.getXp() + xp);
        user.setWeeklyXp(user.getWeeklyXp() + xp);
        user.setCoins(user.getCoins() + coins);
        appUserRepository.save(user);
    }

    @Transactional
    public void addManualBonus(Long telegramId, long xp, long coins) {
        AppUser user = appUserRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок с таким Telegram ID не найден."));
        addReward(user, xp, coins);
    }

    @Transactional
    public void resetWeeklyXp() {
        List<AppUser> users = appUserRepository.findAll();
        users.forEach(user -> user.setWeeklyXp(0));
        appUserRepository.saveAll(users);
    }

    public long totalRegisteredUsers() {
        return appUserRepository.countByRegistrationCompletedTrue();
    }

    public List<AppUser> allRegisteredUsers() {
        return appUserRepository.findAll().stream()
                .filter(AppUser::isRegistrationCompleted)
                .collect(Collectors.toList());
    }

    private Long resolveReferral(Long currentTelegramId, Long referredByTelegramId) {
        if (referredByTelegramId == null || referredByTelegramId.equals(currentTelegramId)) {
            return null;
        }
        return referredByTelegramId;
    }

    private void updateTelegramProfile(AppUser user, User telegramUser) {
        user.setTelegramUsername(telegramUser.getUserName());
        user.setTelegramFirstName(telegramUser.getFirstName());
        user.setTelegramLastName(telegramUser.getLastName());
        if (user.getNickname() == null || user.getNickname().isBlank()) {
            String fallback = telegramUser.getUserName();
            user.setNickname(fallback != null && !fallback.isBlank() ? fallback : telegramUser.getFirstName());
        }
    }

    public List<String> csvToList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }
}
