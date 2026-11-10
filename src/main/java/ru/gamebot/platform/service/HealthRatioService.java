package ru.gamebot.platform.service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.HealthRatioSnapshot;
import ru.gamebot.platform.domain.model.PayoutPoolEntry;
import ru.gamebot.platform.domain.repository.AppSettingRepository;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.HealthRatioSnapshotRepository;
import ru.gamebot.platform.domain.repository.PayoutPoolEntryRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class HealthRatioService {

    private static final double MIN_RATIO = 0.1;
    private static final double MAX_RATIO = 1.0;

    private final PayoutPoolEntryRepository payoutPoolEntryRepository;
    private final HealthRatioSnapshotRepository healthRatioSnapshotRepository;
    private final AppUserRepository appUserRepository;
    private final AppSettingRepository appSettingRepository;
    private final ru.gamebot.platform.domain.repository.QuestSubmissionRepository questSubmissionRepository;

    /** Курс считается от EXC активных игроков (не заходили 30+ дней - не в знаменателе). Откат без деплоя: app_settings 'hr.active.only' = false (применится при следующем пересчёте, каждые 4 ч или при пополнении фонда). */
    public static final String ACTIVE_ONLY_SETTING = "hr.active.only";
    public static final int ACTIVE_DAYS = 30;

    @PostConstruct
    @Transactional
    public void initOnStartup() {
        recalculate();
    }

    @Scheduled(cron = "0 0 */4 * * *")
    @Transactional
    public void scheduledRecalculate() {
        recalculate();
    }

    @Transactional
    public void addToPayoutPool(long amountRub, Long adminTelegramId) {
        PayoutPoolEntry entry = new PayoutPoolEntry();
        entry.setAmountRub(amountRub);
        entry.setAddedByTelegramId(adminTelegramId);
        entry.setCreatedAt(LocalDateTime.now());
        payoutPoolEntryRepository.save(entry);
        log.info("Payout pool topped up by {} RUB (admin={})", amountRub, adminTelegramId);
        recalculate();
    }

    @Transactional
    public void deductFromPayoutPool(long amountRub) {
        PayoutPoolEntry entry = new PayoutPoolEntry();
        entry.setAmountRub(-amountRub);
        entry.setCreatedAt(LocalDateTime.now());
        payoutPoolEntryRepository.save(entry);
        log.info("Payout pool deducted by {} RUB (withdrawal paid)", amountRub);
        recalculate();
    }

    @Transactional
    public HealthRatioSnapshot recalculate() {
        long payoutPoolRub = payoutPoolEntryRepository.sumAllAmounts();
        long totalDebtExc = appUserRepository.sumAllCoins();
        // Знаменатель курса: «живой» долг (активные за ACTIVE_DAYS дн.); в снимок по-прежнему пишется ВЕСЬ долг, чтобы метрики Пульса и история были сопоставимы
        long ratioDebtExc = activeOnly() ? getActiveDebtExc() : totalDebtExc;

        double ratio;
        if (ratioDebtExc == 0) {
            ratio = MAX_RATIO;
        } else {
            long totalDebtRub = ratioDebtExc / 100;
            if (totalDebtRub == 0) {
                ratio = MAX_RATIO;
            } else {
                ratio = (double) payoutPoolRub / totalDebtRub;
                ratio = Math.min(ratio, MAX_RATIO);
                ratio = Math.max(ratio, MIN_RATIO);
            }
        }

        HealthRatioSnapshot snapshot = new HealthRatioSnapshot();
        snapshot.setRatio(ratio);
        snapshot.setPayoutPoolRub(payoutPoolRub);
        snapshot.setTotalDebtExc(totalDebtExc);
        snapshot.setCalculatedAt(LocalDateTime.now());
        healthRatioSnapshotRepository.save(snapshot);

        log.info("Health Ratio recalculated: {} (pool={}₽, debt={} EXC, debt in ratio={} EXC)", ratio, payoutPoolRub, totalDebtExc, ratioDebtExc);
        return snapshot;
    }

    public double getCurrentRatio() {
        return healthRatioSnapshotRepository.findTopByOrderByCalculatedAtDesc()
                .map(HealthRatioSnapshot::getRatio)
                .orElse(MAX_RATIO);
    }

    // 3.2 Level B: effective price adjusted by Health Ratio (worse HR → higher EXC price). Живёт здесь
    // (не в RewardService), чтобы ShopLimitService мог считать ТОЧНО ТАК ЖЕ при показе статуса товара
    // и при пре-проверке лимита — без этого общего источника они расходились (см. инцидент 2026-09-24:
    // "Доступно — использовано 0 из 10000" показывался по базовой цене 7900, а реальное списание при
    // оформлении заявки уже проверяло эффективную цену 15608 при просевшем Health Ratio, и заявка
    // блокировалась с виду "необоснованно"). Косметика (avatar_frame) не участвует в экономике фонда —
    // цена всегда базовая.
    public long effectivePrice(ru.gamebot.platform.domain.model.RewardItem item) {
        if ("avatar_frame".equals(item.getPurchaseGroup())) {
            return item.getPriceCoins();
        }
        double ratio = getCurrentRatio();
        // Товары магазина (не категория «Вывод») считаются по сниженному курсу: SHOP_PRICE_RATIO_FACTOR x курс фонда, чтобы магазин был вдвое менее выгоден, чем вывод (решение владельца 06.11.2026, курс фонда поднят до 90%). Звёзды (категория «Вывод») - по реальному курсу.
        if (!"Вывод".equals(item.getCategory())) {
            if (tiersEnabled()) {
                // 08.11.2026 (владелец): цена магазина для не-партнёра равноценна для проекта выводу не-партнёра (себестоимость 1 EXC одинакова):
                // базовая цена EXC = закупка x 1,18 x 1,2 x 100 = 141,6 x закупка, вывод = курс не-партнёра / 100 руб. за EXC, значит ratio = 1,416 x курс не-партнёра
                ratio = SHOP_PARITY_FACTOR * Math.min(1.0, ratio / NONPARTNER_RATE_REF);
            } else {
                ratio = ratio * SHOP_PRICE_RATIO_FACTOR;
            }
        }
        long price = ratio >= 1.0 ? item.getPriceCoins() : Math.round(item.getPriceCoins() / ratio);
        if ("telegram_stars".equals(item.getPurchaseGroup())) {
            price = Math.round(price * STARS_PRICE_FACTOR);
        }
        return price;
    }

    /** Общий множитель цены вывода звёздами (07.11.2026, владелец): с Pass x1,12, без Pass x1,12 x 1,25 = x1,4 к базовой цене (базовая цена = закупка звезды 1,58 ₽, то есть выше прежней, но чуть выгоднее рублей при рыночной цене звезды). */
    public static final double STARS_PRICE_FACTOR = 1.12;

    /** Коэффициент курса для цен магазина: 1,0 = изначальная формула (покупка в магазине на ~17% хуже вывода). С 06.11.2026 был 0,6 («вдвое хуже вывода»), по решению владельца возвращён к 1,0 после введения доплаты звёздами. Меньше 1 - дороже в токенах, больше - дешевле (1,2 = на уровне вывода). От реального курса фонда соотношение не зависит. */
    public static final double SHOP_PRICE_RATIO_FACTOR = 0.8;

    /** Курс вывода не-партнёра (дублирует NONPARTNER_RATE / NONPARTNER_RATE_REF в GamePlatformBot: менять вместе) и коэффициент паритета магазина: 1,416 x 0,45. */
    public static final double NONPARTNER_RATE_REF = 0.9;
    public static final double SHOP_PARITY_FACTOR = 1.416 * 0.45;
    // 07.11.2026 (владелец): 0,8 вместо 1,0 - товары магазина на 25% дороже в токенах (игрок получает ~75% стоимости против 100% при выводе через партнёров), чтобы магазин оставался менее выгодным, чем вывод на карту банка-партнёра или биржу.

    /** Наценка на вывод звёздами для игроков без активного EGC Pass (05.11.2026): с Pass звёзды по обычной цене, то есть скидка Pass = отмена наценки (на 20% дешевле). */
    public static final double STARS_NON_PASS_MARKUP = 1.25;

    /** Цена позиции для конкретного игрока: для звёзд без активного EGC Pass - с наценкой (округление до сотен), для остального как effectivePrice. Показ, лимиты и списание должны брать цену отсюда. */
    public long effectivePriceFor(ru.gamebot.platform.domain.model.AppUser user, ru.gamebot.platform.domain.model.RewardItem item) {
        if (user != null && item != null && "telegram_stars".equals(item.getPurchaseGroup()) && tiersEnabled()) {
            return starsPriceFor(user, item, isPartner(user));
        }
        if (user != null && item != null && !"telegram_stars".equals(item.getPurchaseGroup()) && !"avatar_frame".equals(item.getPurchaseGroup())
                && !"Вывод".equals(item.getCategory()) && tiersEnabled() && isPartner(user)) {
            // Партнёр (уровни вывода, 07.11.2026): товары магазина по коэффициенту PARTNER_SHOP_RATIO_FACTOR вместо SHOP_PRICE_RATIO_FACTOR - дешевле в токенах
            double partnerRatio = getCurrentRatio() * PARTNER_SHOP_RATIO_FACTOR;
            return partnerRatio >= 1.0 ? item.getPriceCoins() : Math.round(item.getPriceCoins() / partnerRatio);
        }
        long price = effectivePrice(item);
        if (starsMarkupApplies(user, item)) {
            return Math.round(price * STARS_NON_PASS_MARKUP / 100.0) * 100;
        }
        return price;
    }

    /** Коэффициент цен магазина для партнёра (режим уровней): 1,0 против 0,8 у остальных - токены партнёра в магазине ценятся ~75% против ~60%. */
    public static final double PARTNER_SHOP_RATIO_FACTOR = 1.0;

    /** Цена позиции вывода звёздами по уровню (07.11.2026): asPartner - цена партнёра (база x1,12, фиксированно); иначе - без партнёра: база x2,2 (с Pass x1,8), делённая на min(1, курс фонда / 0,9), чтобы звёзды не были выгоднее вывода в рубли по 45% (закупка звезды 1,58 ₽). Нужна и для показа цен другого уровня на экране звёзд. */
    public long starsPriceFor(ru.gamebot.platform.domain.model.AppUser user, ru.gamebot.platform.domain.model.RewardItem item, boolean asPartner) {
        double base = item.getPriceCoins();
        double mult;
        if (asPartner) {
            mult = STARS_PRICE_FACTOR;
        } else {
            double fundScale = Math.min(1.0, getCurrentRatio() / 0.9);
            mult = (starsMarkupApplies(user, item) ? 2.2 : 1.8) / Math.max(0.1, fundScale);
        }
        return Math.round(base * mult / 100.0) * 100;
    }

    /** Цена позиции для партнёра (для показа не-партнёру рядом с его ценой, 10.11.2026); null - уровней нет, игрок уже партнёр, позиция без партнёрской цены или она не ниже цены игрока. */
    public Long partnerPriceFor(ru.gamebot.platform.domain.model.AppUser user, ru.gamebot.platform.domain.model.RewardItem item) {
        if (user == null || item == null || !tiersEnabled() || isPartner(user)) return null;
        if ("avatar_frame".equals(item.getPurchaseGroup()) || "Вывод".equals(item.getCategory())) return null;
        long own = effectivePriceFor(user, item);
        long partner;
        if ("telegram_stars".equals(item.getPurchaseGroup())) {
            partner = starsPriceFor(user, item, true);
        } else {
            double partnerRatio = getCurrentRatio() * PARTNER_SHOP_RATIO_FACTOR;
            partner = partnerRatio >= 1.0 ? item.getPriceCoins() : Math.round(item.getPriceCoins() / partnerRatio);
        }
        return partner < own ? partner : null;
    }

    /** Включены уровни вывода (app_settings 'wd.tiers.enabled', scripts/wd-tiers-on.sql). */
    public boolean tiersEnabled() {
        return appSettingRepository.findById("wd.tiers.enabled")
                .map(ru.gamebot.platform.domain.model.AppSetting::getValue)
                .map(v -> "true".equalsIgnoreCase(v.trim()))
                .orElse(false);
    }

    /** Партнёр: у игрока одобренный квест банка-партнёра (WithdrawalPartners.ALL). */
    public boolean isPartner(ru.gamebot.platform.domain.model.AppUser user) {
        if (user == null || user.getId() == null) return false;
        for (WithdrawalPartners.Bank bank : WithdrawalPartners.ALL) {
            // С 10.11.2026: партнёр только после выплаты банка (bonusPaidAt), одобренной первой части недостаточно
            if (questSubmissionRepository.countBonusPaid(user.getId(), bank.mission().gameName()) > 0) return true;
        }
        return false;
    }

    /** Первая часть партнёрского квеста одобрена, но банк ещё не выплатил бонус: партнёрский статус и курс 100% включатся после выплаты. */
    public boolean isPartnerPending(ru.gamebot.platform.domain.model.AppUser user) {
        if (user == null || user.getId() == null || isPartner(user)) return false;
        for (WithdrawalPartners.Bank bank : WithdrawalPartners.ALL) {
            if (questSubmissionRepository.findApprovedByUserAndGame(user.getId(), bank.mission().gameName()).stream().anyMatch(s -> s.getBonusPaidAt() == null)) return true;
        }
        return false;
    }

    /** Наценка на звёзды действует: позиция - звёзды и у игрока нет активного EGC Pass. */
    public boolean starsMarkupApplies(ru.gamebot.platform.domain.model.AppUser user, ru.gamebot.platform.domain.model.RewardItem item) {
        if (user == null || item == null || !"telegram_stars".equals(item.getPurchaseGroup())) return false;
        LocalDateTime passUntil = user.getEgcPassActiveUntil();
        return passUntil == null || !LocalDateTime.now().isBefore(passUntil);
    }

    /** Реальная стоимость одной звезды Telegram у реселлера в рублях (RewardSeeder откалиброван так же: 50 ⭐ = 79 ₽). Только для показа эквивалента игроку. */
    public static final double STAR_RUB = 1.58;

    /** Приблизительный эквивалент суммы EXC в звёздах по текущему курсу фонда (только подпись для игрока, ничего не начисляет и не списывает).
     *  До 10 звёзд - один знак после запятой, дальше целое. Пустая строка для нуля. */
    public String starsApprox(long exc) {
        return starsApprox(exc, getCurrentRatio());
    }

    public static String starsApprox(long exc, double ratio) {
        if (exc <= 0) return "";
        double stars = exc * ratio / 100.0 / STAR_RUB;
        java.util.Locale ru = java.util.Locale.forLanguageTag("ru");
        if (stars < 0.1) return "≈ меньше 0,1 ⭐";
        if (stars < 10) return "≈ " + String.format(ru, "%.1f", stars) + " ⭐";
        return "≈ " + String.format(ru, "%,d", Math.round(stars)) + " ⭐";
    }

    public long getPayoutPoolRub() {
        return payoutPoolEntryRepository.sumAllAmounts();
    }

    public long getTotalDebtExc() {
        return appUserRepository.sumAllCoins();
    }

    /** EXC на балансах игроков, активных за ACTIVE_DAYS дней: знаменатель курса фонда. */
    public long getActiveDebtExc() {
        LocalDateTime since = LocalDateTime.now().minusDays(ACTIVE_DAYS);
        return appUserRepository.sumCoinsActiveSince(since.toLocalDate(), since);
    }

    private boolean activeOnly() {
        return appSettingRepository.findById(ACTIVE_ONLY_SETTING)
                .map(ru.gamebot.platform.domain.model.AppSetting::getValue)
                .map(v -> !"false".equalsIgnoreCase(v.trim()))
                .orElse(true);
    }

    public long applyRatio(long baseCoins) {
        double ratio = getCurrentRatio();
        return Math.max(1, Math.round(baseCoins * ratio));
    }
}
