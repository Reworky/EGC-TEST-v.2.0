package ru.gamebot.platform.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.ExcTransactionRepository;
import ru.gamebot.platform.service.ExcTransactionService;

/**
 * ОДНОРАЗОВАЯ починка (2026-08-31): списывает лишние EXC у аккаунтов, успевших пройти теперь-одноразовые
 * квесты (антифрод-фикс 2026-08-30) несколько раз ДО фикса — по отчёту "🕵️ Повторы разовых квестов".
 * Суммы зафиксированы вручную по состоянию отчёта на момент правки. Идемпотентно: перед списанием
 * проверяет, нет ли уже точно такой же записи в истории EXC (по описанию) — безопасно переживает
 * несколько деплоев подряд, не спишет дважды. После подтверждения пользователем можно удалить файл
 * целиком (тот же паттерн, что и у других одноразовых починок в проекте, см. QuestSeeder.stuckByRenameBug).
 * Уведомление пользователям НЕ отправляется — пользователь проекта решил разослать текст сам.
 */
@Slf4j
@Component
@Order(5)
@RequiredArgsConstructor
public class OneTimeQuestAbuseCorrectionRunner implements CommandLineRunner {

    private final AppUserRepository appUserRepository;
    private final ExcTransactionRepository excTransactionRepository;
    private final ExcTransactionService excTx;

    private record Correction(long telegramId, long amount, String questTitle, long count) {}

    @Override
    @Transactional
    public void run(String... args) {
        Correction[] corrections = {
                new Correction(6357542899L, 24000, "Достигни 5 000 Кубков", 7),
                new Correction(7796187632L, 20000, "Достигни 5 000 Кубков", 6),
                new Correction(6194258766L, 4500, "Собери 25 000 золота или эликсира за день", 4),
                new Correction(8273903048L, 8000, "Достигни 5 000 Кубков", 3),
                new Correction(5283408496L, 4000, "Достигни 5 000 Кубков", 2),
        };

        for (Correction c : corrections) {
            appUserRepository.findByTelegramId(c.telegramId()).ifPresentOrElse(user -> {
                String description = "Коррекция за повторное прохождение разового квеста \"" + c.questTitle()
                        + "\" (" + c.count() + " раз, до антифрод-фикса)";
                if (excTransactionRepository.existsByUserAndDescription(user, description)) {
                    log.info("[OneTimeQuestAbuseCorrection] Already applied for {}, skipping", c.telegramId());
                    return;
                }
                // Не уводим баланс в минус — если игрок уже потратил/вывел часть EXC, списываем сколько есть.
                long toDeduct = Math.min(c.amount(), user.getCoins());
                if (toDeduct <= 0) {
                    log.info("[OneTimeQuestAbuseCorrection] Skipped {} — balance already 0", c.telegramId());
                    return;
                }
                user.setCoins(user.getCoins() - toDeduct);
                appUserRepository.save(user);
                excTx.log(user, -toDeduct, ExcTransactionService.CONFISCATE, description);
                log.info("[OneTimeQuestAbuseCorrection] Deducted {} EXC from {}", toDeduct, c.telegramId());
            }, () -> log.warn("[OneTimeQuestAbuseCorrection] User not found: {}", c.telegramId()));
        }
    }
}
