package ru.gamebot.platform.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Random;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.ExcTransactionRepository;

/**
 * Рекламное («премиум») колесо: спин выдаётся за просмотр рекламы (см. UserService.claimPendingAdReward,
 * цель показа WHEEL), билеты обычного колеса не тратятся и дневной лимит обычного колеса не занимают -
 * поэтому спины НЕ пишутся в wheel_spin_log (его считает WheelService.MAX_SPINS_PER_DAY).
 *
 * Экономика: показ рекламы приносит ~0,5-1 руб., средний приз держим около 40 EXC (~0,4 руб. номиналом,
 * реальные обязательства ниже из-за Health Ratio). Таблица призов (веса из 100 000, шансы показаны игроку
 * в мини-аппе, AdWheelSection.jsx - при правке менять оба места): 10 EXC 32,98%, 20 - 28%, 30 - 18%, 50 - 10%,
 * 100 - 5,5%, 200 - 2,5%, билет - 2,5%, 500 - 0,5%, ДЖЕКПОТ 5000 - 0,02% (1 из 5000).
 * Среднее = sum(вес x EXC)/100000, билет считаем по ~300 EXC: ~35,8 EXC, из них джекпот даёт ~1 EXC.
 *
 * Защита от разброса (шансы для игроков опубликованы с этими оговорками): джекпот не чаще раза в 30 дней на игрока
 * и не более JACKPOT_PER_DAY в сутки на всех - иначе выпадает 500 EXC. Гарантия: если GUARANTEE_EVERY-1 спинов подряд
 * без приза от 100 EXC (или билета), следующий такой приз выдаётся принудительно (стоит ~0,5 EXC на спин).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdWheelService {

    private static final Random RNG = new Random();

    // Ключи призов совпадают с клиентом (miniapp AdWheelSection.jsx): EXC-суммы и TICKET.
    private static final int TOTAL = 100_000;
    private static final int[]    WEIGHTS = {32980, 28000, 18000, 10000,  5500,  2500,  2500,   500,   20};
    private static final long[]   EXC     = {   10,    20,    30,    50,   100,   200,     0,   500, 5000};
    private static final String[] TYPES   = {"EXC", "EXC", "EXC", "EXC", "EXC", "EXC", "TICKET", "EXC", "EXC"};
    private static final int JACKPOT = 8;
    private static final int JACKPOT_FALLBACK = 7; // 500 EXC

    public static final int GUARANTEE_EVERY = 20;
    private static final long GUARANTEE_MIN_EXC = 100;
    private static final int JACKPOT_PER_USER_DAYS = 30;
    private static final int JACKPOT_PER_DAY = 2;

    private final AppUserRepository appUserRepository;
    private final ExcTransactionService excTx;
    private final ExcTransactionRepository excTxRepository;
    private final WheelService wheelService;
    private final ApplicationEventPublisher eventPublisher;

    public record AdSpinResult(String type, long excAmount, String label, int spinsLeft, boolean jackpot) {}

    /** Сколько спинов осталось до гарантированного приза от 100 EXC (1 - следующий спин гарантирован). */
    public static int spinsUntilGuarantee(AppUser user) {
        return Math.max(1, GUARANTEE_EVERY - user.getAdWheelDrySpins());
    }

    private static boolean isBigPrize(int sector) {
        return "TICKET".equals(TYPES[sector]) || EXC[sector] >= GUARANTEE_MIN_EXC;
    }

    private int rollSector(boolean guaranteed) {
        if (guaranteed) {
            // Гарантированный приз - из «крупных» секторов кроме джекпота, с теми же относительными весами
            int sum = 0;
            for (int i = 0; i < WEIGHTS.length; i++) if (i != JACKPOT && isBigPrize(i)) sum += WEIGHTS[i];
            int roll = RNG.nextInt(sum);
            int cumulative = 0;
            for (int i = 0; i < WEIGHTS.length; i++) {
                if (i == JACKPOT || !isBigPrize(i)) continue;
                cumulative += WEIGHTS[i];
                if (roll < cumulative) return i;
            }
            return JACKPOT_FALLBACK;
        }
        int roll = RNG.nextInt(TOTAL);
        int cumulative = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            cumulative += WEIGHTS[i];
            if (roll < cumulative) return i;
        }
        return WEIGHTS.length - 1;
    }

    /** Джекпот разрешён, если игрок не выигрывал его последние 30 дней и сегодня выдано меньше JACKPOT_PER_DAY. Дневной счёт
     *  не под общей блокировкой (блокируется только строка игрока), поэтому при одновременных выигрышах лимит может
     *  превыситься на единицу - это допустимо. */
    private boolean jackpotAllowed(AppUser user) {
        LocalDateTime last = user.getAdWheelJackpotAt();
        if (last != null && last.isAfter(LocalDateTime.now().minusDays(JACKPOT_PER_USER_DAYS))) return false;
        long today = excTxRepository.countByDescriptionAndCreatedAtGreaterThanEqual(
                descriptionFor(EXC[JACKPOT]), LocalDate.now().atStartOfDay());
        return today < JACKPOT_PER_DAY;
    }

    private static String descriptionFor(long excAmount) {
        return "Рекламное колесо: " + excAmount + " EXC";
    }

    @Transactional
    public AdSpinResult spin(Long userId) {
        // Блокировка строки игрока: два одновременных запроса не потратят один и тот же спин дважды
        AppUser user = appUserRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок не найден."));
        if (user.getAdWheelSpins() < 1) {
            throw new IllegalArgumentException("Нет доступных спинов - посмотрите рекламный ролик.");
        }

        int sector = rollSector(user.getAdWheelDrySpins() + 1 >= GUARANTEE_EVERY);
        if (sector == JACKPOT && !jackpotAllowed(user)) {
            sector = JACKPOT_FALLBACK;
        }

        String type = TYPES[sector];
        long excAmount = EXC[sector];
        boolean jackpot = sector == JACKPOT;
        String label;
        user.setAdWheelSpins(user.getAdWheelSpins() - 1);
        user.setAdWheelDrySpins(isBigPrize(sector) ? 0 : user.getAdWheelDrySpins() + 1);
        if ("TICKET".equals(type)) {
            label = "🎟 Билет колеса фортуны";
            wheelService.addTickets(user, 1, "Рекламное колесо");
        } else {
            label = excAmount + " EXC";
            user.setCoins(user.getCoins() + excAmount);
            excTx.log(user, excAmount, ExcTransactionService.BONUS, descriptionFor(excAmount));
        }
        if (jackpot) {
            user.setAdWheelJackpotAt(LocalDateTime.now());
        }
        appUserRepository.save(user);
        if (jackpot) {
            try {
                eventPublisher.publishEvent(new ru.gamebot.platform.event.AdWheelJackpotEvent(
                        this, user.getTelegramId(), user.getNickname(), excAmount));
            } catch (Exception e) {
                log.warn("[AdWheel] Failed to publish jackpot event for user {}", userId, e);
            }
        }
        return new AdSpinResult(type, excAmount, label, user.getAdWheelSpins(), jackpot);
    }
}
