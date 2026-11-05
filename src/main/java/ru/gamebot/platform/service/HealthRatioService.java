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
        if (ratio >= 1.0) {
            return item.getPriceCoins();
        }
        return Math.round(item.getPriceCoins() / ratio);
    }

    /** Наценка на вывод звёздами для игроков без активного EGC Pass (05.11.2026): с Pass звёзды по обычной цене, то есть скидка Pass = отмена наценки (на 20% дешевле). */
    public static final double STARS_NON_PASS_MARKUP = 1.25;

    /** Цена позиции для конкретного игрока: для звёзд без активного EGC Pass - с наценкой (округление до сотен), для остального как effectivePrice. Показ, лимиты и списание должны брать цену отсюда. */
    public long effectivePriceFor(ru.gamebot.platform.domain.model.AppUser user, ru.gamebot.platform.domain.model.RewardItem item) {
        long price = effectivePrice(item);
        if (starsMarkupApplies(user, item)) {
            return Math.round(price * STARS_NON_PASS_MARKUP / 100.0) * 100;
        }
        return price;
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
