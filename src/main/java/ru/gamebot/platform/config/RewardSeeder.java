package ru.gamebot.platform.config;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.domain.model.RewardItem;
import ru.gamebot.platform.domain.repository.RewardItemRepository;

/**
 * Upserts reward shop items on startup.
 * Prices are ×15 relative to the original economy (aligned with quest reward scale-up).
 *
 * Категория "Подарочные карты" — цифровые карты Steam / PSN, курс 100 EXC = 1 ₽ + ~20% premium.
 * Категория "EGC Эксклюзив"   — брендированные призы клуба, требуют более высокого уровня.
 */
@Slf4j
@Component
@Order(3)
@RequiredArgsConstructor
public class RewardSeeder implements CommandLineRunner {

    private final RewardItemRepository rewardItemRepository;

    @Override
    @Transactional
    public void run(String... args) {

        // ── Подарочные карты ────────────────────────────────────────────────────

        seed("Gift Card Steam — 100 ₽",
                "Цифровой код пополнения Steam-кошелька на 100 рублей. "
                        + "Доставляется в Telegram после одобрения заявки администратором. "
                        + "Код действует на аккаунты любого региона.",
                "Подарочные карты", 12_000);

        seed("Gift Card Steam — 250 ₽",
                "Цифровой код пополнения Steam-кошелька на 250 рублей. "
                        + "Доставляется в Telegram после одобрения заявки администратором.",
                "Подарочные карты", 28_000);

        seed("Gift Card Steam — 500 ₽",
                "Цифровой код пополнения Steam-кошелька на 500 рублей. "
                        + "Доставляется в Telegram после одобрения заявки администратором.",
                "Подарочные карты", 55_000);

        seed("Gift Card PSN — 500 ₽",
                "Цифровой код пополнения PlayStation Network на 500 рублей. "
                        + "Доставляется в Telegram после одобрения заявки администратором.",
                "Подарочные карты", 55_000);

        // ── EGC Эксклюзив ───────────────────────────────────────────────────────

        seed("Значок EGC — «Ветеран»",
                "Эксклюзивный цифровой значок в профиле EGC — «Ветеран клуба». "
                        + "Подтверждает участие в клубе на ранних этапах. Выдаётся вручную.",
                "EGC Эксклюзив", 20_000);

        seed("Футболка EGC — брендированная",
                "Брендированная футболка EXPERIENCE GAMING CLUB. "
                        + "Доставка по России. Уточнение размера и адреса — через поддержку.",
                "EGC Эксклюзив", 75_000);

        seed("EGC Council — статус на 1 месяц",
                "Доступ к закрытым Council-квестам с повышенными наградами на 30 дней. "
                        + "Число мест ограничено — статус активируется администратором вручную.",
                "EGC Эксклюзив", 100_000);
    }

    private void seed(String title, String description, String category, long priceCoins) {
        rewardItemRepository.findByTitle(title).ifPresentOrElse(
                existing -> {
                    if (existing.getPriceCoins() != priceCoins) {
                        existing.setPriceCoins(priceCoins);
                        rewardItemRepository.save(existing);
                        log.info("[RewardSeeder] Updated price '{}': {} EXC", title, priceCoins);
                    }
                },
                () -> {
                    RewardItem item = new RewardItem();
                    item.setTitle(title);
                    item.setDescription(description);
                    item.setCategory(category);
                    item.setPriceCoins(priceCoins);
                    item.setActive(true);
                    item.setCreatedAt(LocalDateTime.now());
                    rewardItemRepository.save(item);
                    log.info("[RewardSeeder] Created '{}': {} EXC", title, priceCoins);
                }
        );
    }
}
