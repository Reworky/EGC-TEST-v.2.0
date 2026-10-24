package ru.gamebot.platform.api.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.PayoutFeedDto;
import ru.gamebot.platform.domain.model.RewardRequest;
import ru.gamebot.platform.service.ExchangeRateService;
import ru.gamebot.platform.service.HealthRatioService;
import ru.gamebot.platform.service.RewardService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * «Лента выплат» для мини-аппа — те же данные и та же логика, что в боте (GamePlatformBot.payoutFeedText):
 * последние 8 выплаченных выводов с замаскированными никами и итоги за 24 часа / 7 дней.
 * Суммы в рублях считаются как в боте: для Stars — по базовой цене позиции 1:1, для остальных —
 * зафиксированная при заявке сумма либо текущий курс фонда.
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/payouts")
@RequiredArgsConstructor
public class PayoutFeedController {

    private static final int FEED_SIZE = 8;
    private static final String REVIEWS_URL = "https://t.me/egc_payouts";

    private final RewardService rewardService;
    private final HealthRatioService healthRatioService;
    private final ExchangeRateService exchangeRateService;

    private long[] cachedTotals;
    private LocalDateTime cachedAt;

    @GetMapping("/feed")
    public PayoutFeedDto feed() {
        LocalDateTime now = LocalDateTime.now();
        // Выводы и выданные награды магазина одной лентой по времени; итоги в рублях ниже считаются только по выводам.
        List<RewardRequest> recent = new ArrayList<>(rewardService.recentApprovedWithdrawals(FEED_SIZE));
        try {
            recent.addAll(rewardService.recentIssuedRewards(FEED_SIZE));
        } catch (Exception e) {
            // награды - дополнение к ленте, сбой их выборки не должен ломать выплаты
        }
        recent.sort((a, b) -> {
            LocalDateTime ta = payoutMoment(a), tb = payoutMoment(b);
            if (ta == null) return tb == null ? 0 : 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });
        List<PayoutFeedDto.Item> items = new ArrayList<>();
        for (RewardRequest r : recent.subList(0, Math.min(FEED_SIZE, recent.size()))) {
            LocalDateTime when = payoutMoment(r);
            boolean reward = isShopReward(r);
            items.add(PayoutFeedDto.Item.builder()
                    .rub(reward ? rewardService.shopRewardRub(r) : rub(r))
                    .amountText(reward ? r.getRewardItem().getTitle() : amountText(r))
                    .method(reward ? "reward" : method(r))
                    .minutesAgo(when == null ? -1 : Math.max(0, Duration.between(when, now).toMinutes()))
                    .name(maskNickname(r.getUser() == null ? null : r.getUser().getNickname()))
                    .build());
        }

        // Итоги: выводы и выданные награды магазина (по рублёвому эквиваленту на момент выдачи).
        long dayRub = 0, dayCount = 0, weekRub = 0;
        List<RewardRequest> week = new ArrayList<>(rewardService.approvedWithdrawalsSince(now.minusDays(7)));
        try {
            week.addAll(rewardService.issuedRewardsSince(now.minusDays(7)));
        } catch (Exception e) {
            // награды - дополнение к итогам, сбой их выборки не должен ломать выплаты
        }
        for (RewardRequest r : week) {
            long rub = isShopReward(r) ? rewardService.shopRewardRub(r) : rub(r);
            weekRub += rub;
            LocalDateTime when = payoutMoment(r);
            if (when != null && when.isAfter(now.minusHours(24))) {
                dayRub += rub;
                dayCount++;
            }
        }

        long[] totals = totalsCached(now);

        return PayoutFeedDto.builder()
                .items(items)
                .dayRub(dayRub)
                .dayCount(dayCount)
                .weekRub(weekRub)
                .weekCount(week.size())
                .totalRub(totals[0])
                .totalCount(totals[1])
                .reviewsUrl(REVIEWS_URL)
                .build();
    }

    /** Итог «выплачено за всё время»: сумма по всем выплаченным выводам, кэш на 10 минут (эндпоинт публичный). Сбой подсчёта не ломает ленту. */
    private synchronized long[] totalsCached(LocalDateTime now) {
        if (cachedTotals != null && cachedAt != null && cachedAt.isAfter(now.minusMinutes(10))) return cachedTotals;
        try {
            List<RewardRequest> all = new ArrayList<>(rewardService.approvedWithdrawalsSince(LocalDateTime.of(2000, 1, 1, 0, 0)));
            all.addAll(rewardService.issuedRewardsSince(LocalDateTime.of(2000, 1, 1, 0, 0)));
            long sum = 0;
            for (RewardRequest r : all) sum += isShopReward(r) ? rewardService.shopRewardRub(r) : rub(r);
            cachedTotals = new long[]{sum, all.size()};
            cachedAt = now;
        } catch (Exception e) {
            // лента важнее итога: отдаём прошлое значение или нули
            if (cachedTotals == null) cachedTotals = new long[]{0, 0};
        }
        return cachedTotals;
    }

    private static LocalDateTime payoutMoment(RewardRequest r) {
        return r.getPaidAt() != null ? r.getPaidAt() : r.getCreatedAt();
    }

    private static boolean isShopReward(RewardRequest r) {
        return !"Вывод".equals(r.getRewardItem().getCategory());
    }

    private static boolean isStars(RewardRequest r) {
        return "telegram_stars".equals(r.getRewardItem().getPurchaseGroup());
    }

    private static String method(RewardRequest r) {
        if (isStars(r)) return "stars";
        String details = r.getPayoutDetails();
        boolean crypto = details != null && (details.startsWith("TON") || details.startsWith("USDT"));
        return crypto ? "ton" : "sbp";
    }

    /** Как в боте (GamePlatformBot.payoutAmountText): СБП и старые USDT - рубли, Stars - число звёзд, GRAM - ~количество монет по текущему курсу. */
    private String amountText(RewardRequest r) {
        try {
            if (isStars(r)) {
                long stars = rewardService.starsAmountOf(r);
                if (stars > 0) return String.format("%,d", stars).replace(',', ' ') + " ⭐";
            } else if ("ton".equals(method(r)) && r.getPayoutDetails().startsWith("TON")) {
                java.math.BigDecimal gram = exchangeRateService.rubToTon(java.math.BigDecimal.valueOf(rub(r)));
                return "~" + gram.stripTrailingZeros().toPlainString() + " GRAM";
            }
        } catch (Exception e) {
            // лента не должна падать из-за курса: покажем рубли
        }
        return String.format("%,d", rub(r)).replace(',', ' ') + " ₽";
    }

    private long rub(RewardRequest r) {
        if (isStars(r)) {
            return Math.round(r.getRewardItem().getPriceCoins() / 100.0);
        }
        return r.getFixedRubValue() != null
                ? r.getFixedRubValue()
                : Math.round(r.getRewardItem().getPriceCoins() * healthRatioService.getCurrentRatio() / 100.0);
    }

    /** Ник без полной деанонимизации: первые 2 и последний символы («Пч…ь»); эмодзи не рвём — по code point. */
    private static String maskNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) return "Игрок";
        int[] cps = nickname.trim().codePoints().toArray();
        if (cps.length <= 3) return new String(cps, 0, 1) + "…";
        return new String(cps, 0, 2) + "…" + new String(cps, cps.length - 1, 1);
    }
}
