package ru.gamebot.platform.service;

import java.util.Random;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.repository.AppUserRepository;

/**
 * Рекламное («премиум») колесо: спин выдаётся за просмотр рекламы (см. UserService.claimPendingAdReward,
 * цель показа WHEEL), билеты обычного колеса не тратятся и дневной лимит обычного колеса не занимают —
 * поэтому спины НЕ пишутся в wheel_spin_log (его считает WheelService.MAX_SPINS_PER_DAY).
 *
 * Экономика: показ рекламы приносит ~0,5–1 ₽, поэтому средний приз держим около 40 EXC (≈0,4 ₽ номиналом,
 * реальные обязательства ниже из-за Health Ratio) — чуть выше плоских 30 EXC за обычный показ, ради азарта.
 * Джекпот 500 EXC — 0,5%. Таблица должна суммироваться в 1000; при правке цен пересчитать среднее:
 * sum(вес × EXC)/1000, билет колеса считаем по ~300 EXC.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdWheelService {

    private static final Random RNG = new Random();

    // Ключи призов совпадают с клиентом (miniapp AdWheelSection.jsx): EXC-суммы и TICKET.
    private static final int[]    WEIGHTS = {330, 280, 180, 100,  55,  25,  25,   5};
    private static final long[]   EXC     = { 10,  20,  30,  50, 100, 200,   0, 500};
    private static final String[] TYPES   = {"EXC", "EXC", "EXC", "EXC", "EXC", "EXC", "TICKET", "EXC"};

    private final AppUserRepository appUserRepository;
    private final ExcTransactionService excTx;
    private final WheelService wheelService;

    public record AdSpinResult(String type, long excAmount, String label, int spinsLeft) {}

    @Transactional
    public AdSpinResult spin(Long userId) {
        // Блокировка строки игрока: два одновременных запроса не потратят один и тот же спин дважды
        AppUser user = appUserRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("Игрок не найден."));
        if (user.getAdWheelSpins() < 1) {
            throw new IllegalArgumentException("Нет доступных спинов — посмотрите рекламный ролик.");
        }

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
        long excAmount = EXC[sector];
        String label;
        user.setAdWheelSpins(user.getAdWheelSpins() - 1);
        if ("TICKET".equals(type)) {
            label = "🎟 Билет колеса фортуны";
            wheelService.addTickets(user, 1, "Рекламное колесо");
        } else {
            label = excAmount + " EXC";
            user.setCoins(user.getCoins() + excAmount);
            excTx.log(user, excAmount, ExcTransactionService.BONUS, "Рекламное колесо: " + label);
        }
        appUserRepository.save(user);
        return new AdSpinResult(type, excAmount, label, user.getAdWheelSpins());
    }
}
