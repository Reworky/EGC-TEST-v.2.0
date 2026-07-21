package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.RewardItem;
import ru.gamebot.platform.domain.model.WheelSpinLog;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.RewardItemRepository;
import ru.gamebot.platform.domain.repository.WheelSpinLogRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class WheelService {

    public static final int MAX_SPINS_PER_DAY = 10;

    private static final Random RNG = new Random();

    // Cumulative weights out of 1000 (probabilities × 10)
    private static final int[] WEIGHTS    = {300, 250, 200, 120,  80,  30,  15,   5};
    private static final long[] EXC_PRIZES = { 50, 100, 300, 500,1000,2000,   0,   0};
    private static final String[] LABELS  = {
        "🥉 50 EXC", "🥉 100 EXC", "🥈 300 EXC", "🥈 500 EXC",
        "🥇 1 000 EXC", "🥇 2 000 EXC", "💎 XP-буст 24ч", "👑 Рамка аватара"
    };
    private static final String[] TYPES   = {
        "EXC", "EXC", "EXC", "EXC", "EXC", "EXC", "BOOST_24H", "AVATAR_FRAME"
    };

    private final AppUserRepository appUserRepository;
    private final WheelSpinLogRepository wheelSpinLogRepository;
    private final RewardItemRepository rewardItemRepository;
    private final SinkShopService sinkShopService;
    private final ExcTransactionService excTx;

    public record SpinResult(String type, long excAmount, String label) {}

    @Transactional
    public SpinResult spin(AppUser user) {
        if (user.getTickets() < 1) {
            throw new IllegalArgumentException("Недостаточно билетов для кручения.");
        }

        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        long spinsToday = wheelSpinLogRepository.countByUserSince(user, startOfDay);
        if (spinsToday >= MAX_SPINS_PER_DAY) {
            throw new IllegalArgumentException("Достигнут дневной лимит: максимум " + MAX_SPINS_PER_DAY + " кручений в сутки.");
        }

        // Pick sector
        int roll = RNG.nextInt(1000);
        int cumulative = 0;
        int sector = WEIGHTS.length - 1;
        for (int i = 0; i < WEIGHTS.length; i++) {
            cumulative += WEIGHTS[i];
            if (roll < cumulative) {
                sector = i;
                break;
            }
        }

        String type = TYPES[sector];
        long excAmount = EXC_PRIZES[sector];
        String label = LABELS[sector];

        // Deduct ticket
        user.setTickets(user.getTickets() - 1);

        // Apply reward
        switch (type) {
            case "EXC" -> {
                user.setCoins(user.getCoins() + excAmount);
                excTx.log(user, excAmount, ExcTransactionService.BONUS, "Колесо фортуны: " + label);
            }
            case "BOOST_24H" -> {
                if (!sinkShopService.isXpBoostActive(user)) {
                    user.setXpBoostActiveUntil(LocalDateTime.now().plusHours(24));
                } else {
                    // Boost already active — extend by 24h
                    LocalDateTime current = user.getXpBoostActiveUntil();
                    user.setXpBoostActiveUntil(current.plusHours(24));
                }
            }
            case "AVATAR_FRAME" -> {
                List<RewardItem> frames = rewardItemRepository
                        .findAllByActiveTrueAndPurchaseGroupOrderByPriceCoinsAsc("avatar_frame");
                if (!frames.isEmpty()) {
                    RewardItem frame = frames.get(RNG.nextInt(frames.size()));
                    user.setAvatarFrameColor(frame.getAvatarFrameColor());
                    user.setAvatarFrameImage(frame.getAvatarFrameImage());
                    label = "👑 Рамка аватара «" + frame.getTitle() + "»";
                }
            }
        }

        appUserRepository.save(user);

        // Log spin
        WheelSpinLog log = new WheelSpinLog();
        log.setUser(user);
        log.setRewardType(type);
        log.setRewardAmount(excAmount > 0 ? excAmount : null);
        log.setRewardLabel(label);
        wheelSpinLogRepository.save(log);

        return new SpinResult(type, excAmount, label);
    }

    /** Give tickets to a user and save. Called from quest approval and daily bonus. */
    @Transactional
    public void addTickets(AppUser user, int amount, String reason) {
        user.setTickets(user.getTickets() + amount);
        appUserRepository.save(user);
        log.info("[WheelService] +{} ticket(s) to user {} — {}", amount, user.getTelegramId(), reason);
    }
}
