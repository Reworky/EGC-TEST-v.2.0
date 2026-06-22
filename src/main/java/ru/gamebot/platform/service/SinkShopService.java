package ru.gamebot.platform.service;

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

    public static final long PRICE_REROLL = 50;
    public static final long PRICE_BOOST = 200;
    public static final long PRICE_TITLE_BASIC = 100;
    public static final long PRICE_TITLE_RARE = 300;
    public static final long PRICE_TITLE_EPIC = 500;
    public static final long PRICE_INSURANCE = 75;

    private static final int BOOST_DURATION_HOURS = 24;
    private static final int BOOST_PERCENT = 20;

    private final AppUserRepository appUserRepository;

    @Transactional
    public void purchaseReroll(AppUser user) {
        deductCoins(user, PRICE_REROLL);
    }

    @Transactional
    public void purchaseBoost(AppUser user) {
        deductCoins(user, PRICE_BOOST);
        LocalDateTime until = LocalDateTime.now().plusHours(BOOST_DURATION_HOURS);
        user.setExcBoostActiveUntil(until);
        appUserRepository.save(user);
    }

    @Transactional
    public void purchaseTitle(AppUser user, String title, long price) {
        deductCoins(user, price);
        user.setProfileTitle(title);
        appUserRepository.save(user);
    }

    @Transactional
    public void purchaseInsurance(AppUser user) {
        if (user.isRetryInsuranceActive()) {
            throw new IllegalStateException("Страховка уже активна.");
        }
        deductCoins(user, PRICE_INSURANCE);
        user.setRetryInsuranceActive(true);
        appUserRepository.save(user);
    }

    public boolean isBoostActive(AppUser user) {
        return user.getExcBoostActiveUntil() != null
                && LocalDateTime.now().isBefore(user.getExcBoostActiveUntil());
    }

    public int getBoostPercent(AppUser user) {
        return isBoostActive(user) ? BOOST_PERCENT : 0;
    }

    @Transactional
    public void consumeInsurance(AppUser user) {
        user.setRetryInsuranceActive(false);
        appUserRepository.save(user);
    }

    // --- 3.3 Withdrawal limits ---

    public long getMonthlyLimit(long xp) {
        if (xp >= 10000) return 75_000;
        if (xp >= 3000) return 35_000;
        return 15_000;
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
        if (user.getCoins() < price) {
            throw new IllegalArgumentException("Недостаточно EXC. Нужно " + price + ", есть " + user.getCoins() + ".");
        }
        user.setCoins(user.getCoins() - price);
        appUserRepository.save(user);
    }
}
