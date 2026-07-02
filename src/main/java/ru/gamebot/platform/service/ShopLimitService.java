package ru.gamebot.platform.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.RewardItem;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.RewardRequestRepository;

@Service
@RequiredArgsConstructor
public class ShopLimitService {

    private static final long COOLDOWN_SMALL_THRESHOLD = 15_000L;
    private static final long COOLDOWN_MEDIUM_THRESHOLD = 55_000L;
    private static final int COOLDOWN_SMALL_DAYS = 5;
    private static final int COOLDOWN_MEDIUM_DAYS = 14;
    private static final int COOLDOWN_LARGE_DAYS = 30;

    private final RewardRequestRepository rewardRequestRepository;
    private final AppUserRepository appUserRepository;
    private final UserService userService;
    private final SinkShopService sinkShopService;

    /**
     * Проверяет все 4 слоя ограничений. Бросает IllegalArgumentException с текстом для пользователя.
     */
    public void checkAllLimits(AppUser user, RewardItem item) {
        checkLevelAccess(user, item);
        checkItemQuantityLimit(user, item);
        checkMonthlySpendsLimit(user, item);
        checkCooldown(user, item);
    }

    /** Слой 1: уровень доступа */
    public void checkLevelAccess(AppUser user, RewardItem item) {
        if (item.getMinLevelXp() > 0 && user.getXp() < item.getMinLevelXp()) {
            String requiredLevel = userService.getLevelName(item.getMinLevelXp());
            long needed = item.getMinLevelXp() - user.getXp();
            throw new IllegalArgumentException(
                    "🔒 Заблокировано до уровня " + requiredLevel + " — нужно ещё " + needed + " XP.");
        }
    }

    /** Слой 2: лимит количества одного товара */
    public void checkItemQuantityLimit(AppUser user, RewardItem item) {
        String group = item.getPurchaseGroup();
        if (group == null) return;

        LocalDateTime monthStart = LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);

        switch (group) {
            case "gift_card" -> {
                long count = rewardRequestRepository.countActiveByUserAndGroupSince(user, "gift_card", monthStart);
                if (count > 0) {
                    throw new IllegalArgumentException(
                            "🚫 Подарочные карты: лимит 1 штука в месяц уже использован. Доступно с 1-го числа следующего месяца.");
                }
            }
            case "badge_egc" -> {
                long count = rewardRequestRepository.countActiveByUserAndGroupAllTime(user, "badge_egc");
                if (count > 0) {
                    throw new IllegalArgumentException("🚫 Значок EGC можно получить только 1 раз.");
                }
            }
            case "tshirt_egc" -> {
                long count = rewardRequestRepository.countActiveByUserAndGroupAllTime(user, "tshirt_egc");
                if (count > 0) {
                    throw new IllegalArgumentException("🚫 Футболку EGC можно заказать только 1 раз.");
                }
            }
            case "council_egc" -> {
                LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
                long count = rewardRequestRepository.countActiveCouncilSince(user, thirtyDaysAgo);
                if (count > 0) {
                    throw new IllegalArgumentException(
                            "🚫 EGC Council: статус уже активен. Продление доступно через 30 дней после последней покупки.");
                }
            }
            default -> {
                // Игровые валюты: 1 покупка на игру в месяц
                long count = rewardRequestRepository.countActiveByUserAndGroupSince(user, group, monthStart);
                if (count > 0) {
                    throw new IllegalArgumentException(
                            "🚫 Лимит: 1 покупка на эту игру в месяц уже использован. Доступно с 1-го числа следующего месяца.");
                }
            }
        }
    }

    /** Слой 3: общий месячный лимит трат (через существующий механизм вывода) */
    public void checkMonthlySpendsLimit(AppUser user, RewardItem item) {
        long remaining = sinkShopService.getRemainingWithdrawalLimit(user);
        if (item.getPriceCoins() > remaining) {
            throw new IllegalArgumentException(
                    "📊 Достигнут месячный лимит трат в магазине. Доступно ещё: " + remaining + " EXC. Сбрасывается 1-го числа каждого месяца.");
        }
    }

    /** Слой 4: cooldown между покупками по ценовому диапазону */
    public void checkCooldown(AppUser user, RewardItem item) {
        long price = item.getPriceCoins();
        LocalDateTime now = LocalDateTime.now();

        if (price <= COOLDOWN_SMALL_THRESHOLD) {
            if (user.getShopCooldownSmallUntil() != null && now.isBefore(user.getShopCooldownSmallUntil())) {
                long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(now, user.getShopCooldownSmallUntil()) + 1;
                throw new IllegalArgumentException("⏳ Следующая покупка в этой категории доступна через " + daysLeft + " дн.");
            }
        } else if (price <= COOLDOWN_MEDIUM_THRESHOLD) {
            if (user.getShopCooldownMediumUntil() != null && now.isBefore(user.getShopCooldownMediumUntil())) {
                long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(now, user.getShopCooldownMediumUntil()) + 1;
                throw new IllegalArgumentException("⏳ Следующая покупка в этой категории доступна через " + daysLeft + " дн.");
            }
        } else {
            if (user.getShopCooldownLargeUntil() != null && now.isBefore(user.getShopCooldownLargeUntil())) {
                long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(now, user.getShopCooldownLargeUntil()) + 1;
                throw new IllegalArgumentException("⏳ Следующая покупка в этой категории доступна через " + daysLeft + " дн.");
            }
        }
    }

    /** Устанавливает cooldown после успешной покупки */
    @Transactional
    public void recordPurchaseCooldown(AppUser user, long price) {
        LocalDateTime now = LocalDateTime.now();
        if (price <= COOLDOWN_SMALL_THRESHOLD) {
            user.setShopCooldownSmallUntil(now.plusDays(COOLDOWN_SMALL_DAYS));
        } else if (price <= COOLDOWN_MEDIUM_THRESHOLD) {
            user.setShopCooldownMediumUntil(now.plusDays(COOLDOWN_MEDIUM_DAYS));
        } else {
            user.setShopCooldownLargeUntil(now.plusDays(COOLDOWN_LARGE_DAYS));
        }
        appUserRepository.save(user);
    }

    /**
     * Возвращает статусную строку для карточки товара.
     * Порядок: уровень → cooldown → количественный лимит → месячный лимит → доступно.
     */
    public String getItemStatus(AppUser user, RewardItem item) {
        // Слой 1
        if (item.getMinLevelXp() > 0 && user.getXp() < item.getMinLevelXp()) {
            String requiredLevel = userService.getLevelName(item.getMinLevelXp());
            long needed = item.getMinLevelXp() - user.getXp();
            return "🔒 Заблокировано до уровня " + requiredLevel + " — нужно ещё " + needed + " XP";
        }

        // Слой 4
        long price = item.getPriceCoins();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cooldownUntil = null;
        if (price <= COOLDOWN_SMALL_THRESHOLD) cooldownUntil = user.getShopCooldownSmallUntil();
        else if (price <= COOLDOWN_MEDIUM_THRESHOLD) cooldownUntil = user.getShopCooldownMediumUntil();
        else cooldownUntil = user.getShopCooldownLargeUntil();

        if (cooldownUntil != null && now.isBefore(cooldownUntil)) {
            long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(now, cooldownUntil) + 1;
            return "⏳ Cooldown — следующая покупка через " + daysLeft + " дн.";
        }

        // Слой 2
        String group = item.getPurchaseGroup();
        if (group != null) {
            LocalDateTime monthStart = LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
            boolean blocked = switch (group) {
                case "gift_card" -> rewardRequestRepository.countActiveByUserAndGroupSince(user, "gift_card", monthStart) > 0;
                case "badge_egc" -> rewardRequestRepository.countActiveByUserAndGroupAllTime(user, "badge_egc") > 0;
                case "tshirt_egc" -> rewardRequestRepository.countActiveByUserAndGroupAllTime(user, "tshirt_egc") > 0;
                case "council_egc" -> rewardRequestRepository.countActiveCouncilSince(user, now.minusDays(30)) > 0;
                default -> rewardRequestRepository.countActiveByUserAndGroupSince(user, group, monthStart) > 0;
            };
            if (blocked) {
                if ("badge_egc".equals(group) || "tshirt_egc".equals(group)) {
                    return "🚫 Уже получено";
                }
                return "🚫 Лимит месяца исчерпан";
            }
        }

        // Слой 3
        long remaining = sinkShopService.getRemainingWithdrawalLimit(user);
        if (item.getPriceCoins() > remaining) {
            return "🚫 Недоступно — достигнут месячный лимит трат (" + remaining + " EXC осталось)";
        }

        long used = user.getMonthlyWithdrawnExc();
        long limit = sinkShopService.getMonthlyLimit(user.getXp());
        return "✅ Доступно — использовано " + used + " из " + limit + " EXC в этом месяце";
    }
}
