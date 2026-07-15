package ru.gamebot.platform.api.controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.gamebot.platform.api.dto.DailyBonusResponseDto;
import ru.gamebot.platform.api.dto.ShopActionResponseDto;
import ru.gamebot.platform.api.dto.TonQuoteDto;
import ru.gamebot.platform.api.dto.WalletDto;
import ru.gamebot.platform.api.dto.WithdrawalRequestDto;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.RewardRequest;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.service.ExchangeRateService;
import ru.gamebot.platform.service.HealthRatioService;
import ru.gamebot.platform.service.RewardService;
import ru.gamebot.platform.service.SinkShopService;
import ru.gamebot.platform.service.UserService;

@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final long MIN_WITHDRAWAL = 5_000;

    private final AppUserRepository appUserRepository;
    private final UserService userService;
    private final SinkShopService sinkShopService;
    private final HealthRatioService healthRatioService;
    private final ExchangeRateService exchangeRateService;
    private final RewardService rewardService;

    @Data
    public static class WithdrawRubRequest {
        private long amount;
        private String requisites;
    }

    @Data
    public static class WithdrawTonRequest {
        private long amount;
        private String walletAddress;
    }

    @GetMapping
    public ResponseEntity<WalletDto> wallet(@AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        int ratioPercent = (int) Math.round(healthRatioService.getCurrentRatio() * 100);
        long nextDailyBonus = Math.min(150L + (long) user.getStreakDays() * 50, 500L);
        return ResponseEntity.ok(WalletDto.builder()
                .coins(user.getCoins())
                .excBonusPercent(userService.getExcBonusPercent(user.getXp()))
                .tickets(user.getTickets())
                .xp(user.getXp())
                .weeklyXp(user.getWeeklyXp())
                .healthRatioPercent(ratioPercent)
                .monthlyWithdrawalLimit(sinkShopService.getMonthlyLimit(user.getXp()))
                .remainingWithdrawalLimit(sinkShopService.getRemainingWithdrawalLimit(user))
                .dailyBonusAvailable(userService.isDailyBonusAvailable(user))
                .streakDays(user.getStreakDays())
                .nextDailyBonusExc(nextDailyBonus)
                .fixedRubBalance(user.getFixedRubBalance())
                .build());
    }

    @PostMapping("/daily-bonus")
    public ResponseEntity<DailyBonusResponseDto> claimDailyBonus(@AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        UserService.DailyBonusResult result = userService.claimDailyBonus(user);
        if (result == null) {
            return ResponseEntity.ok(DailyBonusResponseDto.builder()
                    .success(false)
                    .message("Бонус уже получен сегодня.")
                    .newBalance(user.getCoins())
                    .build());
        }
        return ResponseEntity.ok(DailyBonusResponseDto.builder()
                .success(true)
                .message(result.milestoneText() != null ? result.milestoneText() : "Ежедневный бонус получен!")
                .totalExc(result.totalExc())
                .dailyExc(result.dailyExc())
                .milestoneExc(result.milestoneExc())
                .xpBonus(result.xpBonus())
                .streakDays(result.streakDays())
                .milestoneText(result.milestoneText())
                .newBalance(user.getCoins())
                .build());
    }

    @GetMapping("/ton-quote")
    public ResponseEntity<TonQuoteDto> tonQuote(@RequestParam long amount, @AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        long rubles = resolveRubValue(user, amount).rubles();
        BigDecimal rublesDecimal = BigDecimal.valueOf(rubles);
        BigDecimal tonRate = exchangeRateService.getTonRubRate();
        BigDecimal tonAmount = exchangeRateService.rubToTon(rublesDecimal);
        return ResponseEntity.ok(TonQuoteDto.builder()
                .rubles(rubles)
                .tonAmount(tonAmount.toPlainString())
                .tonRate(tonRate.setScale(2, RoundingMode.HALF_DOWN).toPlainString())
                .usingFallback(exchangeRateService.isUsingFallback())
                .build());
    }

    @PostMapping("/withdraw/rub")
    public ResponseEntity<ShopActionResponseDto> withdrawRub(
            @AuthenticationPrincipal Long telegramId, @RequestBody WithdrawRubRequest body) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String requisites = body.getRequisites() != null ? body.getRequisites().trim() : "";
        if (requisites.length() < 6) {
            return errorResponse("Реквизиты слишком короткие. Укажите банк и номер телефона.");
        }
        ShopActionResponseDto validation = validateAmount(user, body.getAmount());
        if (validation != null) return ResponseEntity.ok(validation);
        if (rewardService.hasWithdrawalTodayOrPending(user)) {
            return errorResponse("Лимит: 1 заявка на вывод в сутки. Следующую можно создать через 24 часа после предыдущей.");
        }
        RubResolution resolution = resolveRubValue(user, body.getAmount());
        long rubles = resolution.rubles();
        try {
            RewardRequest req = rewardService.createWithdrawalRequestWithDetails(user, body.getAmount(), rubles, resolution.fixedRubUsed(), requisites);
            return ResponseEntity.ok(ShopActionResponseDto.builder()
                    .success(true)
                    .message("Заявка на вывод В-" + (req.getDisplayId() != null ? req.getDisplayId() : req.getId())
                            + " принята! " + body.getAmount() + " EXC → ~" + rubles + " ₽. Администратор обработает в течение 24 часов.")
                    .build());
        } catch (IllegalArgumentException e) {
            return errorResponse(e.getMessage());
        }
    }

    @PostMapping("/withdraw/ton")
    public ResponseEntity<ShopActionResponseDto> withdrawTon(
            @AuthenticationPrincipal Long telegramId, @RequestBody WithdrawTonRequest body) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String wallet = body.getWalletAddress() != null ? body.getWalletAddress().trim() : "";
        if (wallet.length() < 20 || wallet.contains(" ")) {
            return errorResponse("Некорректный адрес кошелька. Адрес TON должен начинаться с UQ... или EQ... и содержать 48 символов.");
        }
        ShopActionResponseDto validation = validateAmount(user, body.getAmount());
        if (validation != null) return ResponseEntity.ok(validation);
        if (rewardService.hasWithdrawalTodayOrPending(user)) {
            return errorResponse("Лимит: 1 заявка на вывод в сутки. Следующую можно создать через 24 часа после предыдущей.");
        }
        RubResolution resolution = resolveRubValue(user, body.getAmount());
        long rubles = resolution.rubles();
        try {
            RewardRequest req = rewardService.createTonWithdrawalRequest(user, body.getAmount(), rubles, resolution.fixedRubUsed(), wallet);
            BigDecimal tonAmount = exchangeRateService.rubToTon(BigDecimal.valueOf(rubles));
            return ResponseEntity.ok(ShopActionResponseDto.builder()
                    .success(true)
                    .message("Заявка на вывод В-" + (req.getDisplayId() != null ? req.getDisplayId() : req.getId())
                            + " принята! " + body.getAmount() + " EXC → ~" + rubles + " ₽ → ~" + tonAmount + " GRAM. Администратор обработает в течение 24 часов.")
                    .build());
        } catch (IllegalArgumentException e) {
            return errorResponse(e.getMessage());
        }
    }

    @GetMapping("/withdrawals")
    public ResponseEntity<List<WithdrawalRequestDto>> withdrawals(@AuthenticationPrincipal Long telegramId) {
        AppUser user = appUserRepository.findByTelegramId(telegramId).orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        List<WithdrawalRequestDto> result = rewardService.findUserRequests(user).stream()
                .filter(r -> "Вывод".equals(r.getRewardItem().getCategory()))
                .map(this::toWithdrawalDto)
                .toList();
        return ResponseEntity.ok(result);
    }

    private WithdrawalRequestDto toWithdrawalDto(RewardRequest r) {
        String payoutDetails = r.getPayoutDetails();
        boolean isCrypto = payoutDetails != null && (payoutDetails.startsWith("TON") || payoutDetails.startsWith("USDT"));
        String method = isCrypto ? (payoutDetails.startsWith("USDT") ? "USDT · TON" : "GRAM (TON)") : "Рубли";
        String details = payoutDetails;
        if (isCrypto && payoutDetails != null) {
            String[] parts = payoutDetails.split(":");
            details = parts.length > 1 ? parts[1] : payoutDetails;
        }
        return WithdrawalRequestDto.builder()
                .id(r.getId())
                .displayId(r.getDisplayId() != null ? r.getDisplayId() : r.getId())
                .amountExc(r.getRewardItem().getPriceCoins())
                .status(r.getStatus().name())
                .adminComment(r.getAdminComment())
                .createdAt(r.getCreatedAt() != null ? r.getCreatedAt().format(FMT) : null)
                .method(method)
                .details(details)
                .build();
    }

    /** Возвращает ответ с ошибкой, если сумма не проходит проверки, иначе null. */
    private ShopActionResponseDto validateAmount(AppUser user, long amount) {
        if (amount < MIN_WITHDRAWAL) {
            return ShopActionResponseDto.builder().success(false)
                    .message("Минимальная сумма вывода — 5 000 EXC.").build();
        }
        long remaining = sinkShopService.getRemainingWithdrawalLimit(user);
        if (amount > remaining) {
            return ShopActionResponseDto.builder().success(false)
                    .message("Превышен месячный лимит. Доступно: " + remaining + " EXC.").build();
        }
        if (amount > user.getCoins()) {
            return ShopActionResponseDto.builder().success(false)
                    .message("Недостаточно EXC. Баланс: " + user.getCoins() + " EXC.").build();
        }
        return null;
    }

    private ResponseEntity<ShopActionResponseDto> errorResponse(String message) {
        return ResponseEntity.ok(ShopActionResponseDto.builder().success(false).message(message).build());
    }

    private record RubResolution(long rubles, long fixedRubUsed) {}

    /**
     * Рассчитывает рублёвый эквивалент для вывода excAmount EXC.
     * Если зафиксированный баланс (fixedRubBalance) даёт больше, чем текущий HR —
     * используется гарантированная сумма. Иначе — текущий HR.
     * fixedRubUsed — сколько нужно списать с fixedRubBalance при создании заявки.
     */
    private RubResolution resolveRubValue(AppUser user, long excAmount) {
        double ratio = healthRatioService.getCurrentRatio();
        long hrRub = Math.round(excAmount * ratio / 100.0);

        long fixedRubBalance = user.getFixedRubBalance();
        if (fixedRubBalance <= 0) return new RubResolution(hrRub, 0);

        long totalCoins = user.getCoins();
        if (totalCoins <= 0) return new RubResolution(hrRub, 0);

        // Пропорциональная доля фиксированного баланса, относящаяся к выводимым EXC
        long usedFixed = Math.round((double) fixedRubBalance * excAmount / totalCoins);
        usedFixed = Math.min(usedFixed, fixedRubBalance);

        if (hrRub >= usedFixed) {
            // Текущий HR не хуже гарантированного — fixed баланс не расходуем
            return new RubResolution(hrRub, 0);
        }
        // Fixed rate выгоднее — используем гарантированную сумму
        return new RubResolution(usedFixed, usedFixed);
    }
}
