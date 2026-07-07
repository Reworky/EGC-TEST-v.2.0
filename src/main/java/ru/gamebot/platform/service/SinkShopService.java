package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;

@Service
@RequiredArgsConstructor
public class SinkShopService {

    public static final long PRICE_REROLL = 2_000;
    public static final long PRICE_BOOST = 3_000;
    public static final long PRICE_TITLE_BASIC = 1_500;
    public static final long PRICE_TITLE_RARE = 4_500;
    public static final long PRICE_TITLE_EPIC = 7_500;
    public static final long PRICE_INSURANCE = 1_500;

    public static final long PRICE_XP_BOOST_24H = 3_000;
    public static final long PRICE_EXC_BOOST_24H = 3_000;
    public static final long PRICE_DOUBLE_BOOST_24H = 5_000;
    public static final long PRICE_XP_BOOST_72H = 7_500;
    public static final long PRICE_EXC_BOOST_72H = 7_500;
    public static final long PRICE_EXTRA_SLOT = 2_000;
    public static final long PRICE_COOLDOWN_REMOVAL = 1_500;
    public static final long PRICE_GIFT_BOOST = 4_500;

    private static final int BOOST_DURATION_HOURS = 24;
    private static final int BOOST_PERCENT = 20;
    private static final int MAX_DAILY_BOOSTS = 3;
    private static final int MAX_DAILY_REROLLS = 3;
    private static final int MAX_DAILY_COOLDOWN_REMOVALS = 2;
    private static final int MAX_DAILY_GIFTS_SENT = 2;
    private static final int MAX_DAILY_GIFTS_RECEIVED = 1;

    private final AppUserRepository appUserRepository;
    private final ExcTransactionService excTx;

    @Transactional
    public void purchaseReroll(AppUser user) {
        deductCoins(user, PRICE_REROLL, "Реролл квеста");
    }

    @Transactional
    public void purchaseBoost(AppUser user) {
        purchaseExcBoostTimed(user, 24);
    }

    @Transactional
    public void purchaseTitle(AppUser user, String title, long price) {
        deductCoins(user, price, "Титул: " + title);
        user.setProfileTitle(title);
        appUserRepository.save(user);
    }

    @Transactional
    public void purchaseInsurance(AppUser user) {
        if (user.isRetryInsuranceActive()) {
            throw new IllegalStateException("Страховка уже активна.");
        }
        deductCoins(user, PRICE_INSURANCE, "Страховка повторной попытки");
        user.setRetryInsuranceActive(true);
        appUserRepository.save(user);
    }

    @Transactional
    public void purchaseXpBoost(AppUser user, int hours) {
        if (isXpBoostActive(user)) {
            throw new IllegalArgumentException("XP-буст уже активен.");
        }
        int dailyBoosts = getDailyCount(user.getDailyBoostCount(), user.getDailyBoostDate());
        if (dailyBoosts >= MAX_DAILY_BOOSTS) {
            throw new IllegalArgumentException("Достигнут дневной лимит покупки бустов (" + MAX_DAILY_BOOSTS + " в сутки).");
        }
        long price = hours == 72 ? PRICE_XP_BOOST_72H : PRICE_XP_BOOST_24H;
        deductCoins(user, price, "XP-буст ×" + hours + "ч");
        user.setXpBoostActiveUntil(LocalDateTime.now().plusHours(hours));
        user.setDailyBoostCount(dailyBoosts + 1);
        user.setDailyBoostDate(LocalDate.now());
        appUserRepository.save(user);
    }

    @Transactional
    public void purchaseExcBoostTimed(AppUser user, int hours) {
        if (isExcBoostActive(user)) {
            throw new IllegalArgumentException("EXC-буст уже активен.");
        }
        int dailyBoosts = getDailyCount(user.getDailyBoostCount(), user.getDailyBoostDate());
        if (dailyBoosts >= MAX_DAILY_BOOSTS) {
            throw new IllegalArgumentException("Достигнут дневной лимит покупки бустов (" + MAX_DAILY_BOOSTS + " в сутки).");
        }
        long price = hours == 72 ? PRICE_EXC_BOOST_72H : PRICE_EXC_BOOST_24H;
        deductCoins(user, price, "EXC-буст ×" + hours + "ч");
        user.setExcBoostActiveUntil(LocalDateTime.now().plusHours(hours));
        user.setDailyBoostCount(dailyBoosts + 1);
        user.setDailyBoostDate(LocalDate.now());
        appUserRepository.save(user);
    }

    @Transactional
    public void purchaseDoubleBoost(AppUser user, int hours) {
        if (isXpBoostActive(user)) {
            throw new IllegalArgumentException("XP-буст уже активен. Сначала дождитесь окончания.");
        }
        if (isExcBoostActive(user)) {
            throw new IllegalArgumentException("EXC-буст уже активен. Сначала дождитесь окончания.");
        }
        int dailyBoosts = getDailyCount(user.getDailyBoostCount(), user.getDailyBoostDate());
        if (dailyBoosts + 2 > MAX_DAILY_BOOSTS) {
            throw new IllegalArgumentException("Недостаточно дневного лимита для двойного буста (нужно 2 слота, доступно " + (MAX_DAILY_BOOSTS - dailyBoosts) + ").");
        }
        deductCoins(user, PRICE_DOUBLE_BOOST_24H, "Двойной буст ×" + hours + "ч");
        LocalDateTime until = LocalDateTime.now().plusHours(hours);
        user.setXpBoostActiveUntil(until);
        user.setExcBoostActiveUntil(until);
        user.setDailyBoostCount(dailyBoosts + 2);
        user.setDailyBoostDate(LocalDate.now());
        appUserRepository.save(user);
    }

    @Transactional
    public void purchaseExtraSlot(AppUser user) {
        if (hasExtraSlot(user)) {
            throw new IllegalArgumentException("Доп. слот уже активен до: " + user.getQuestSlotExtraUntil().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")) + ".");
        }
        deductCoins(user, PRICE_EXTRA_SLOT, "Дополнительный слот квеста");
        user.setQuestSlotExtraUntil(LocalDateTime.now().plusHours(48));
        appUserRepository.save(user);
    }

    @Transactional
    public void purchaseCooldownRemoval(AppUser user) {
        int dailyRemovals = getDailyCount(user.getDailyCooldownRemovals(), user.getDailyCooldownDate());
        if (dailyRemovals >= MAX_DAILY_COOLDOWN_REMOVALS) {
            throw new IllegalArgumentException("Достигнут дневной лимит снятий кулдауна (" + MAX_DAILY_COOLDOWN_REMOVALS + " в сутки).");
        }
        if (user.getCooldownBypassGame() != null) {
            throw new IllegalArgumentException("Снятие кулдауна уже активно. Возьмите квест с кулдауном, чтобы использовать его.");
        }
        deductCoins(user, PRICE_COOLDOWN_REMOVAL, "Снятие кулдауна квеста");
        user.setCooldownBypassGame("ANY");
        user.setDailyCooldownRemovals(dailyRemovals + 1);
        user.setDailyCooldownDate(LocalDate.now());
        appUserRepository.save(user);
    }

    @Transactional
    public boolean purchaseGiftBoost(AppUser sender, AppUser recipient) {
        if (sender.getTelegramId().equals(recipient.getTelegramId())) {
            throw new IllegalArgumentException("Нельзя отправить подарок самому себе.");
        }
        int sentToday = getDailyCount(sender.getDailyGiftsSent(), sender.getDailyGiftSentDate());
        if (sentToday >= MAX_DAILY_GIFTS_SENT) {
            throw new IllegalArgumentException("Достигнут дневной лимит отправки подарков (" + MAX_DAILY_GIFTS_SENT + " в сутки).");
        }
        int receivedToday = getDailyCount(recipient.getDailyGiftsReceived(), recipient.getDailyGiftReceivedDate());
        if (receivedToday >= MAX_DAILY_GIFTS_RECEIVED) {
            throw new IllegalArgumentException("Этот игрок уже получил максимум подарков сегодня (" + MAX_DAILY_GIFTS_RECEIVED + " в сутки).");
        }
        deductCoins(sender, PRICE_GIFT_BOOST, "Подарок буст → " + recipient.getNickname());
        sender.setDailyGiftsSent(sentToday + 1);
        sender.setDailyGiftSentDate(LocalDate.now());
        appUserRepository.save(sender);

        LocalDateTime until = LocalDateTime.now().plusHours(24);
        if (isXpBoostActive(recipient) && recipient.getXpBoostActiveUntil().isAfter(until)) {
            recipient.setXpBoostActiveUntil(recipient.getXpBoostActiveUntil().plusHours(24));
        } else {
            recipient.setXpBoostActiveUntil(until);
        }
        recipient.setDailyGiftsReceived(receivedToday + 1);
        recipient.setDailyGiftReceivedDate(LocalDate.now());
        appUserRepository.save(recipient);

        return true;
    }

    public boolean isXpBoostActive(AppUser user) {
        return user.getXpBoostActiveUntil() != null
                && LocalDateTime.now().isBefore(user.getXpBoostActiveUntil());
    }

    public boolean isExcBoostActive(AppUser user) {
        return user.getExcBoostActiveUntil() != null
                && LocalDateTime.now().isBefore(user.getExcBoostActiveUntil());
    }

    public boolean isBoostActive(AppUser user) {
        return isExcBoostActive(user);
    }

    public int getBoostPercent(AppUser user) {
        return isBoostActive(user) ? BOOST_PERCENT : 0;
    }

    public int getXpBoostPercent(AppUser user) {
        return isXpBoostActive(user) ? BOOST_PERCENT : 0;
    }

    public boolean hasExtraSlot(AppUser user) {
        return user.getQuestSlotExtraUntil() != null
                && LocalDateTime.now().isBefore(user.getQuestSlotExtraUntil());
    }

    public boolean hasCooldownBypass(AppUser user, String gameName) {
        // Fix 7: bypass must match the game it was purchased for (or "ANY" for universal)
        String bypass = user.getCooldownBypassGame();
        if (bypass == null) return false;
        return "ANY".equalsIgnoreCase(bypass) || bypass.equalsIgnoreCase(gameName);
    }

    @Transactional
    public void consumeCooldownBypass(AppUser user, String gameName) {
        user.setCooldownBypassGame(null);
        appUserRepository.save(user);
    }

    public long getMaxQuestSlots(AppUser user) {
        return 1;
    }

    @Transactional
    public void consumeInsurance(AppUser user) {
        user.setRetryInsuranceActive(false);
        appUserRepository.save(user);
    }

    // --- 3.3 Withdrawal limits ---

    public long getMonthlyLimit(long xp) {
        if (xp >= 75_000) return 150_000;
        if (xp >= 35_000) return 100_000;
        if (xp >= 15_000) return 80_000;
        if (xp >= 5_000)  return 50_000;
        if (xp >= 1_000)  return 25_000;
        return 10_000;
    }

    public long getRemainingWithdrawalLimit(AppUser user) {
        refreshWithdrawalMonthIfNeeded(user);
        return Math.max(0, getMonthlyLimit(user.getXp()) - user.getMonthlyWithdrawnExc());
    }

    @Transactional
    public void recordWithdrawal(AppUser user, long amount) {
        refreshWithdrawalMonthIfNeeded(user);
        if (amount > getRemainingWithdrawalLimit(user)) {
            throw new IllegalArgumentException("Превышен месячный лимит вывода.");
        }
        user.setMonthlyWithdrawnExc(user.getMonthlyWithdrawnExc() + amount);
        appUserRepository.save(user);
    }

    @Transactional
    public void reverseWithdrawal(AppUser user, long amount) {
        refreshWithdrawalMonthIfNeeded(user);
        user.setMonthlyWithdrawnExc(Math.max(0, user.getMonthlyWithdrawnExc() - amount));
        appUserRepository.save(user);
    }

    @Transactional
    public void resetWithdrawalLimit(AppUser user) {
        user.setMonthlyWithdrawnExc(0);
        appUserRepository.save(user);
    }

    private void refreshWithdrawalMonthIfNeeded(AppUser user) {
        YearMonth now = YearMonth.now();
        if (user.getWithdrawalYear() != now.getYear() || user.getWithdrawalMonth() != now.getMonthValue()) {
            user.setMonthlyWithdrawnExc(0);
            user.setWithdrawalMonth(now.getMonthValue());
            user.setWithdrawalYear(now.getYear());
            appUserRepository.save(user);
        }
    }

    private void deductCoins(AppUser user, long price) {
        deductCoins(user, price, "Покупка в магазине предметов");
    }

    private void deductCoins(AppUser user, long price, String description) {
        if (user.getCoins() < price) {
            throw new IllegalArgumentException("Недостаточно EXC. Нужно " + price + ", есть " + user.getCoins() + ".");
        }
        user.setCoins(user.getCoins() - price);
        appUserRepository.save(user);
        excTx.log(user, -price, ExcTransactionService.SINK, description);
    }

    private int getDailyCount(int count, LocalDate date) {
        return LocalDate.now().equals(date) ? count : 0;
    }
}
