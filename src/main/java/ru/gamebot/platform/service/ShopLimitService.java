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
    // 05.11.2026 (решение владельца): кулдауны сокращены с 5/14/30 до 2/5/10 дней, чтобы EXC можно было тратить чаще
    private static final int COOLDOWN_SMALL_DAYS = 2;
    private static final int COOLDOWN_MEDIUM_DAYS = 5;
    private static final int COOLDOWN_LARGE_DAYS = 10;
    /** Защита вместо ранга: подтверждённый телефон, 3 квеста и 3 дня с регистрации (как для вывода). */
    private static final int SHOP_MIN_QUESTS = 3;
    private static final int SHOP_MIN_ACCOUNT_AGE_DAYS = 3;
    private static final String WITHDRAWAL_CATEGORY = "Вывод";

    /** Текст отказа, если покупатель не прошёл базовую защиту магазина; null - можно. Звёзды (категория «Вывод») проверяются как вывод и сюда не попадают. */
    /** Возраст, с которого EXC можно тратить на товары с прямой денежной ценностью (карты Steam/PSN, пополнение Steam): младше - нельзя
     *  (решение владельца 09.11.2026; Steam сам принимает аккаунты с 13 лет). Оплата своими деньгами сюда не относится. */
    public static final int REAL_VALUE_MIN_AGE = 14;

    // 24.11.2026 (владелец): кейсы CS2 (cs2_case) тоже с 14 лет; других лимитов на кейсы нет - только общий месячный лимит трат и вывода
    private static final java.util.Set<String> REAL_VALUE_GROUPS = java.util.Set.of("gift_card", "steam_topup", "cs2", "cs2_case", "cs2_skin", "cs2_capsule");

    /** null - можно; иначе текст отказа. Возраст не указан не блокирует здесь: профиль проверяет shopEligibilityBlock. */
    public String realValueAgeBlock(AppUser user, RewardItem item) {
        if (item == null || item.getPurchaseGroup() == null || !REAL_VALUE_GROUPS.contains(item.getPurchaseGroup())) return null;
        if (user.getAge() != null && user.getAge() < REAL_VALUE_MIN_AGE) {
            return "🔒 Эту покупку за EXC можно оформить с " + REAL_VALUE_MIN_AGE + " лет.";
        }
        return null;
    }

    public String shopEligibilityBlock(AppUser user) {
        if (user.isBlocked()) return "🔒 Аккаунт заблокирован. Напишите в поддержку.";
        if (user.isWithdrawalFrozen()) return "🔒 Покупки временно приостановлены на проверку. Напишите в поддержку.";
        if (user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()) {
            return "🔒 Для покупки нужно подтвердить номер телефона: откройте «Вывод EXC» и поделитесь контактом.";
        }
        // 08.11.2026 (решение владельца): траты EXC на товары, как и вывод, только с указанными страной и возрастом (оплата деньгами не затрагивается)
        boolean noCountry = user.getCountry() == null || user.getCountry().isBlank();
        boolean noAge = user.getAge() == null;
        if (noCountry || noAge) {
            return "🔒 Для покупки за EXC нужно указать " + (noCountry && noAge ? "страну и возраст" : (noCountry ? "страну" : "возраст"))
                    + " в профиле: «👤 Профиль» → «✏️ Редактировать профиль».";
        }
        boolean tooYoung = user.getCreatedAt() != null && user.getCreatedAt().plusDays(SHOP_MIN_ACCOUNT_AGE_DAYS).isAfter(LocalDateTime.now());
        if (user.getCompletedQuests() < SHOP_MIN_QUESTS || tooYoung) {
            return "🔒 Для покупки нужно выполнить " + SHOP_MIN_QUESTS + " квеста и подождать " + SHOP_MIN_ACCOUNT_AGE_DAYS + " дня после регистрации.";
        }
        return null;
    }

    /** Выключатель выдачи скинов в app_settings: «cs2.skins.paused». По умолчанию (записи нет) выдача ЗАКРЫТА (техработы, владелец 25.11.2026);
     *  открыть: значение 0/false (scripts/cs2-skins-open.sql), закрыть снова: scripts/cs2-skins-close.sql. Применяется сразу, без перезапуска. */
    public static final String SKINS_PAUSED_KEY = "cs2.skins.paused";

    public boolean skinsPaused() {
        return appSettingRepository.findById(SKINS_PAUSED_KEY)
                .map(ru.gamebot.platform.domain.model.AppSetting::getValue)
                .map(v -> !("0".equals(v.trim()) || "false".equalsIgnoreCase(v.trim())))
                .orElse(true);
    }

    /** Отказ, если выдача этого товара сейчас закрыта на техработы (пока только скины); null - можно. Начатые заявки не затрагиваются. */
    public String pauseBlock(RewardItem item) {
        if (item != null && Cs2SkinCatalog.GROUP.equals(item.getPurchaseGroup()) && skinsPaused()) {
            return "🛠 Скины временно недоступны: небольшие технические работы. Скоро откроем, загляните позже.";
        }
        return null;
    }

    /** Игрок из Украины: страна в профиле («Украина», «Україна», «ua»...) или код номера +380. */
    public boolean isUkrainian(AppUser user) {
        if ("Украина".equals(CountryNormalizer.canonical(user.getCountry()))) return true;
        return "Украина".equals(PhoneCountry.fromPhone(user.getPhoneNumber()));
    }

    /** Текст отказа, если выдача кейсов, скинов и капсул CS2 для региона игрока закрыта (25.11.2026, владелец: Украина «регион временно недоступен»); null - доступно. */
    public String regionBlock(AppUser user, RewardItem item) {
        if (item == null || !Cs2CaseAutoIssueService.isAutoGroup(item.getPurchaseGroup())) return null;
        if (isUkrainian(user)) {
            return "🚫 Регион временно недоступен. Выдача кейсов, скинов и капсул в вашем регионе пока закрыта.";
        }
        return null;
    }

    /** Шаг допуска к покупкам за EXC: done - уже выполнен, text - что сделать (для экрана «прежде чем купить», 23.11.2026). */
    public record Requirement(boolean done, String text) {}

    /** Те же условия, что в shopEligibilityBlock (телефон, страна, возраст, 3 квеста, 3 дня с регистрации), но списком с отметками выполненного. */
    public java.util.List<Requirement> shopRequirements(AppUser user) {
        java.util.List<Requirement> list = new java.util.ArrayList<>();
        boolean phone = user.getPhoneNumber() != null && !user.getPhoneNumber().isBlank();
        list.add(new Requirement(phone, "Подтвердить номер телефона"));
        list.add(new Requirement(user.getCountry() != null && !user.getCountry().isBlank(), "Указать страну в профиле"));
        list.add(new Requirement(user.getAge() != null, "Указать возраст в профиле"));
        int quests = user.getCompletedQuests();
        list.add(new Requirement(quests >= SHOP_MIN_QUESTS, "Выполнить " + SHOP_MIN_QUESTS + " квеста (сейчас " + quests + ")"));
        LocalDateTime openAt = user.getCreatedAt() == null ? null : user.getCreatedAt().plusDays(SHOP_MIN_ACCOUNT_AGE_DAYS);
        boolean tooYoung = openAt != null && openAt.isAfter(LocalDateTime.now());
        String wait = "Подождать " + SHOP_MIN_ACCOUNT_AGE_DAYS + " дня после регистрации";
        if (tooYoung) {
            long hoursLeft = java.time.Duration.between(LocalDateTime.now(), openAt).toHours();
            wait += hoursLeft >= 24 ? " (осталось ещё " + (hoursLeft / 24 + 1) + " дн.)" : " (осталось несколько часов)";
        }
        list.add(new Requirement(!tooYoung, wait));
        return list;
    }

    private final RewardRequestRepository rewardRequestRepository;
    private final ru.gamebot.platform.domain.repository.AppSettingRepository appSettingRepository;
    private final AppUserRepository appUserRepository;
    private final UserService userService;
    private final SinkShopService sinkShopService;
    private final HealthRatioService healthRatioService;

    /**
     * Проверяет все 4 слоя ограничений. Бросает IllegalArgumentException с текстом для пользователя.
     */
    public void checkAllLimits(AppUser user, RewardItem item) {
        if ("avatar_frame".equals(item.getPurchaseGroup())) {
            // Рамки аватара — без ограничений вообще (ни количество, ни месячный лимит, ни cooldown), по требованию пользователя.
            return;
        }
        checkLevelAccess(user, item);
        if (!WITHDRAWAL_CATEGORY.equals(item.getCategory())) {
            String eligibility = shopEligibilityBlock(user);
            if (eligibility != null) {
                throw new IllegalArgumentException(eligibility);
            }
            String ageBlock = realValueAgeBlock(user, item);
            if (ageBlock != null) {
                throw new IllegalArgumentException(ageBlock);
            }
        }
        // Вывод в звёздах — это ВЫВОД, а не товар магазина (как рубли/TON, которые вообще не проходят
        // через ShopLimitService). Частоту уже ограничивает отдельная проверка "1 заявка на вывод в
        // сутки" на входе в меню способа вывода — количественный лимит "1 в группу в месяц" и общий
        // ценовой cooldown (который иначе блокировал бы покупку НЕСВЯЗАННЫХ товаров магазина в том же
        // ценовом диапазоне) здесь не нужны и не должны применяться.
        boolean isStarsWithdrawal = "telegram_stars".equals(item.getPurchaseGroup());
        if (!isStarsWithdrawal) {
            checkItemQuantityLimit(user, item);
        }
        checkMonthlySpendsLimit(user, item);
        // Кулдаун между покупками (слой 4) отменён 06.11.2026: лимит трат в месяц и базовая защита уже ограничивают объём
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
            case "council_egc" -> {
                LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
                long count = rewardRequestRepository.countActiveCouncilSince(user, thirtyDaysAgo);
                if (count > 0) {
                    throw new IllegalArgumentException(
                            "🚫 EGC Council: статус уже активен. Продление доступно через 30 дней после последней покупки.");
                }
            }
            case "cs2_case", "cs2_skin", "cs2_capsule" -> {
                String pauseBlock = pauseBlock(item);
                if (pauseBlock != null) throw new IllegalArgumentException(pauseBlock);
                String regionBlock = regionBlock(user, item);
                if (regionBlock != null) throw new IllegalArgumentException(regionBlock);
                // 24.11.2026 (владелец): частоты кейсов и скинов нет, но игрок, не принявший обмен вовремя, 30 дней без них - его пропуск бьёт по аккаунту маркетплейса
                LocalDateTime since = LocalDateTime.now().minusDays(30);
                String marker = Cs2CaseAutoIssueService.NOT_ACCEPTED_MARKER + "%";
                long missed = 0;
                for (String autoGroup : Cs2CaseAutoIssueService.AUTO_GROUPS) {
                    missed += rewardRequestRepository.countRejectedWithMarkerSince(user, autoGroup, marker, since);
                }
                if (missed > 0) {
                    throw new IllegalArgumentException("🚫 Кейсы, скины и капсулы временно недоступны: прошлый обмен не был принят вовремя. Доступно через 30 дней после него; если это ошибка, напишите в поддержку.");
                }
            }
            case "avatar_frame" -> {
                // Лимита количества нет — рамки можно покупать/менять сколько угодно раз, по требованию пользователя.
            }
            default -> {
                // Игровые валюты: лимита количества нет (отменён 06.11.2026) - объём ограничивает лимит трат в месяц
            }
        }
    }

    /** Слой 3: общий месячный лимит трат (через существующий механизм вывода). Сравнивать нужно с
     *  effectivePrice (с поправкой на Health Ratio), а не с базовой ценой товара — иначе эта
     *  пре-проверка (и getItemStatus ниже) молча пропускают то, что реальное списание в
     *  RewardService.createRewardRequest потом всё равно заблокирует по той же самой причине. */
    public void checkMonthlySpendsLimit(AppUser user, RewardItem item) {
        // Товары магазина считаются в отдельный лимит трат (5 x лимит вывода), звёзды (категория «Вывод») - в лимит вывода
        long remaining = WITHDRAWAL_CATEGORY.equals(item.getCategory())
                ? sinkShopService.getRemainingWithdrawalLimit(user) : sinkShopService.getRemainingShopLimit(user);
        long price = healthRatioService.effectivePriceFor(user, item);
        if (price > remaining) {
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

    /** Снимает cooldown ценового диапазона — вызывается при отклонении заявки админом/модератором, чтобы не наказывать игрока за чужой отказ. */
    @Transactional
    public void reverseCooldown(AppUser user, long price) {
        if (price <= COOLDOWN_SMALL_THRESHOLD) {
            user.setShopCooldownSmallUntil(null);
        } else if (price <= COOLDOWN_MEDIUM_THRESHOLD) {
            user.setShopCooldownMediumUntil(null);
        } else {
            user.setShopCooldownLargeUntil(null);
        }
        appUserRepository.save(user);
    }

    /**
     * Возвращает статусную строку для карточки товара.
     * Порядок: уровень → cooldown → количественный лимит → месячный лимит → доступно.
     */
    public String getItemStatus(AppUser user, RewardItem item) {
        if ("avatar_frame".equals(item.getPurchaseGroup())) {
            // Рамки аватара — без ограничений вообще, см. checkAllLimits.
            return "✅ Доступно";
        }
        // Слой 1
        if (item.getMinLevelXp() > 0 && user.getXp() < item.getMinLevelXp()) {
            String requiredLevel = userService.getLevelName(item.getMinLevelXp());
            long needed = item.getMinLevelXp() - user.getXp();
            return "🔒 Заблокировано до уровня " + requiredLevel + " — нужно ещё " + needed + " XP";
        }
        if (!WITHDRAWAL_CATEGORY.equals(item.getCategory())) {
            String eligibility = shopEligibilityBlock(user);
            if (eligibility != null) return eligibility;
            String ageBlock = realValueAgeBlock(user, item);
            if (ageBlock != null) return ageBlock;
        }

        boolean isStarsWithdrawal = "telegram_stars".equals(item.getPurchaseGroup());
        LocalDateTime now = LocalDateTime.now();

        // Слой 4 (кулдаун) отменён 06.11.2026.

        // Слой 2 — не применяется к выводу в звёздах, см. checkAllLimits.
        String group = item.getPurchaseGroup();
        if (group != null && !isStarsWithdrawal) {
            LocalDateTime monthStart = LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
            boolean blocked = switch (group) {
                case "gift_card" -> rewardRequestRepository.countActiveByUserAndGroupSince(user, "gift_card", monthStart) > 0;
                case "badge_egc" -> rewardRequestRepository.countActiveByUserAndGroupAllTime(user, "badge_egc") > 0;
                case "council_egc" -> rewardRequestRepository.countActiveCouncilSince(user, now.minusDays(30)) > 0;
                default -> false;
            };
            if (blocked) {
                if ("badge_egc".equals(group)) {
                    return "🚫 Уже получено";
                }
                return "🚫 Лимит месяца исчерпан";
            }
        }

        // Слой 3 — сравнивать с effectivePrice (Health Ratio), не с базовой ценой, см. checkMonthlySpendsLimit.
        long remaining = WITHDRAWAL_CATEGORY.equals(item.getCategory())
                ? sinkShopService.getRemainingWithdrawalLimit(user) : sinkShopService.getRemainingShopLimit(user);
        long effectivePrice = healthRatioService.effectivePriceFor(user, item);
        if (effectivePrice > remaining) {
            return "🚫 Недоступно — достигнут месячный лимит трат (" + remaining + " EXC осталось)";
        }

        long used = user.getMonthlyWithdrawnExc();
        long limit = sinkShopService.getMonthlyLimit(user);
        return "✅ Доступно — использовано " + used + " из " + limit + " EXC в этом месяце";
    }
}
