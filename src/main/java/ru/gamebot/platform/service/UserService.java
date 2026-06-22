package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.User;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.SupportTicket;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.QuestSubmissionRepository;
import ru.gamebot.platform.domain.repository.RewardRequestRepository;
import ru.gamebot.platform.domain.repository.SupportAttachmentRepository;
import ru.gamebot.platform.domain.repository.SupportTicketRepository;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final List<LevelTier> LEVEL_TIERS = List.of(
            new LevelTier(1, "Новичок", 0, 0),
            new LevelTier(2, "Игрок", 1_000, 5),
            new LevelTier(3, "Ветеран", 5_000, 10),
            new LevelTier(4, "Элита", 15_000, 15),
            new LevelTier(5, "Легенда", 35_000, 20),
            new LevelTier(6, "Герой EXPERIENCE", 75_000, 25),
            new LevelTier(7, "Чемпион EXPERIENCE", 150_000, 30),
            new LevelTier(8, "Амбассадор EXPERIENCE", 300_000, 50)
    );

    private final AppUserRepository appUserRepository;
    private final QuestSubmissionRepository questSubmissionRepository;
    private final RewardRequestRepository rewardRequestRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final SupportAttachmentRepository supportAttachmentRepository;

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
        user.setTickets(0);
        user.setCompletedQuests(0);
        user.setInvitedFriends(0);
        user.setStreakDays(0);
        user.setProfileCompleted(false);
        user.setRegistrationCompleted(false);
        user.setStaffRole("USER");
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

    public int getLevelNumber(long xp) {
        return resolveLevelTier(xp).number();
    }

    public String getLevelName(long xp) {
        return resolveLevelTier(xp).name();
    }

    public int getExcBonusPercent(long xp) {
        return resolveLevelTier(xp).excBonusPercent();
    }

    public long currentLevelFloor(long xp) {
        return resolveLevelTier(xp).minXp();
    }

    public long nextLevelCeiling(long xp) {
        LevelTier current = resolveLevelTier(xp);
        int nextIndex = current.number();
        if (nextIndex >= LEVEL_TIERS.size()) {
            return current.minXp();
        }
        return LEVEL_TIERS.get(nextIndex).minXp();
    }

    public List<String> getAchievements(AppUser user) {
        return Stream.of(
                user.getCompletedQuests() >= 1 ? "🏅 Первое задание" : null,
                user.getCompletedQuests() >= 10 ? "🔥 10 заданий" : null,
                user.getCompletedQuests() >= 100 ? "👑 100 заданий" : null,
                user.getInvitedFriends() >= 1 ? "🤝 Первый реферал" : null,
                user.getInvitedFriends() >= 10 ? "🚀 10 рефералов" : null,
                user.getXp() >= 35_000 ? "🌟 Легенда клуба" : null
        ).filter(item -> item != null).toList();
    }

    @Transactional
    public AppUser completeRegistration(AppUser user, String nickname, Integer age, String country,
                                        List<String> platforms, List<String> interests) {
        user.setNickname(nickname);
        user.setAge(age);
        user.setCountry(country);
        user.setPlatformsCsv(String.join(", ", platforms));
        user.setInterestsCsv(interests.isEmpty() ? "Не выбраны" : String.join(", ", interests));
        user.setProfileCompleted(true);
        user.setRegistrationCompleted(false);
        return appUserRepository.save(user);
    }

    @Transactional
    public AppUser activateAccount(AppUser user) {
        user.setProfileCompleted(true);
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

    // Called on channel subscription — counts the invite, no EXC bonus yet
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
        appUserRepository.save(referrer);
        // 200 EXC to the invited user is granted on first quest completion (see QuestService)
        // 10% referrer bonus per quest is also handled in QuestService
        invitedUser.setReferralRewardProcessed(true);
        appUserRepository.save(invitedUser);
    }

    // 3.5: 200 EXC bonus to invited user on their first approved quest
    @Transactional
    public boolean grantFirstQuestReferralBonus(AppUser invitedUser) {
        if (invitedUser.getReferredByTelegramId() == null) {
            return false;
        }
        if (invitedUser.getCompletedQuests() != 0) {
            return false; // only on first quest (completedQuests is incremented before this call)
        }
        addReward(invitedUser, 0, 200);
        return true;
    }

    @Transactional
    public RewardGrant addReward(AppUser user, long xp, long coins) {
        return addReward(user, xp, coins, 0);
    }

    @Transactional
    public RewardGrant addReward(AppUser user, long xp, long coins, long tickets) {
        RewardGrant rewardGrant = previewReward(user, xp, coins, tickets);
        user.setXp(user.getXp() + xp);
        user.setWeeklyXp(user.getWeeklyXp() + xp);
        user.setCoins(user.getCoins() + rewardGrant.totalExc());
        user.setTickets(user.getTickets() + tickets);
        appUserRepository.save(user);
        return rewardGrant;
    }

    @Transactional
    public RewardGrant addManualBonus(Long telegramId, long xp, long coins, long tickets) {
        AppUser user = appUserRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок с таким Telegram ID не найден."));
        return addReward(user, xp, coins, tickets);
    }

    @Transactional
    public BalanceDebit debitManualBalance(Long telegramId, long xp, long coins, long tickets) {
        AppUser user = appUserRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок с таким Telegram ID не найден."));

        if (user.getXp() < xp) {
            throw new IllegalArgumentException("Недостаточно XP для списания.");
        }
        if (user.getCoins() < coins) {
            throw new IllegalArgumentException("Недостаточно EXC для списания.");
        }
        if (user.getTickets() < tickets) {
            throw new IllegalArgumentException("Недостаточно билетов для списания.");
        }

        user.setXp(user.getXp() - xp);
        user.setWeeklyXp(Math.max(0, user.getWeeklyXp() - xp));
        user.setCoins(user.getCoins() - coins);
        user.setTickets(user.getTickets() - tickets);
        appUserRepository.save(user);
        return new BalanceDebit(xp, coins, tickets);
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

    public List<AppUser> allUsersSorted() {
        return appUserRepository.findAll().stream()
                .sorted(Comparator.comparing(AppUser::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(AppUser::getTelegramId, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }

    @Transactional
    public AppUser updateStaffRole(Long telegramId, String staffRole) {
        AppUser user = appUserRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок с таким Telegram ID не найден."));
        user.setStaffRole(staffRole);
        return appUserRepository.save(user);
    }

    @Transactional
    public AppUser clearPersonalProgress(Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок с таким Telegram ID не найден."));

        questSubmissionRepository.deleteAllByUser(user);
        rewardRequestRepository.deleteAllByUser(user);

        List<SupportTicket> tickets = supportTicketRepository.findAllByUser(user);
        for (SupportTicket ticket : tickets) {
            supportAttachmentRepository.deleteAllByTicket(ticket);
        }
        supportTicketRepository.deleteAllByUser(user);

        user.setNickname(null);
        user.setAge(null);
        user.setCountry(null);
        user.setPlatformsCsv(null);
        user.setInterestsCsv(null);
        user.setProfileCompleted(false);
        user.setRegistrationCompleted(false);
        user.setXp(0);
        user.setWeeklyXp(0);
        user.setCoins(0);
        user.setTickets(0);
        user.setCompletedQuests(0);
        user.setInvitedFriends(0);
        user.setStreakDays(0);
        user.setReferredByTelegramId(null);
        user.setReferralRewardProcessed(false);
        user.setLastActivityDate(null);
        return appUserRepository.save(user);
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

    public RewardGrant previewReward(AppUser user, long xp, long coins, long tickets) {
        long resultingXp = user.getXp() + xp;
        int excBonusPercent = getExcBonusPercent(resultingXp);
        long bonusExc = coins * excBonusPercent / 100;
        return new RewardGrant(xp, coins, bonusExc, coins + bonusExc, tickets, excBonusPercent);
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

    private LevelTier resolveLevelTier(long xp) {
        LevelTier current = LEVEL_TIERS.get(0);
        for (LevelTier tier : LEVEL_TIERS) {
            if (xp >= tier.minXp()) {
                current = tier;
            } else {
                break;
            }
        }
        return current;
    }

    public List<AppUser> getFraudSuspects() {
        return appUserRepository.findAllByFraudSuspectTrue();
    }

    public long countFraudSuspects() {
        return appUserRepository.findAllByFraudSuspectTrue().size();
    }

    @Transactional
    public void clearFraudSuspect(Long telegramId) {
        appUserRepository.findByTelegramId(telegramId).ifPresent(user -> {
            user.setFraudSuspect(false);
            appUserRepository.save(user);
        });
    }

    public record RewardGrant(long xp, long baseExc, long bonusExc, long totalExc, long tickets, int excBonusPercent) {
    }

    public record BalanceDebit(long xp, long exc, long tickets) {
    }

    private record LevelTier(int number, String name, long minXp, int excBonusPercent) {
    }
}
