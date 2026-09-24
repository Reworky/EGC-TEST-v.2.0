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

        double ratio;
        if (totalDebtExc == 0) {
            ratio = MAX_RATIO;
        } else {
            long totalDebtRub = totalDebtExc / 100;
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

        log.info("Health Ratio recalculated: {} (pool={}₽, debt={} EXC)", ratio, payoutPoolRub, totalDebtExc);
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

    public long getPayoutPoolRub() {
        return payoutPoolEntryRepository.sumAllAmounts();
    }

    public long getTotalDebtExc() {
        return appUserRepository.sumAllCoins();
    }

    public long applyRatio(long baseCoins) {
        double ratio = getCurrentRatio();
        return Math.max(1, Math.round(baseCoins * ratio));
    }
}
