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
                "EGC Эксклюзив", 100_000, null);

        // ── Игровые валюты ───────────────────────────────────────────────────────

        seed("PUBG PC — 200 G-Coin",
                "200 G-Coin для PUBG PC. Доставляется в виде подарочного кода в Telegram. "
                        + "Код активируется в меню пополнения PUBG PC самостоятельно. Срок доставки — до 24 ч.",
                "Игровые валюты", 13_000, null);

        seed("PUBG Mobile — 60 UC",
                "60 UC (Unknown Cash) для PUBG Mobile. Пополнение через midasbuy.com по вашему Player ID. "
                        + "Срок доставки — до 24 ч.",
                "Игровые валюты", 13_000,
                "Укажите ваш PUBG Mobile Player ID.\n\n"
                        + "Где найти: откройте профиль в игре → ваш ID указан под никнеймом.\n\n"
                        + "Введите Player ID:");

        seed("EA FC 26 — 500 FC Points",
                "500 FC Points для EA FC 26 (PS5 или Xbox). Ключ активируется в PS Store или Xbox Store. "
                        + "Ключи для PS5 и Xbox — разные, укажите вашу платформу. Срок доставки — до 24 ч.",
                "Игровые валюты", 20_000,
                "Укажите вашу платформу для EA FC 26.\n\n"
                        + "Напишите: PS5 или Xbox");

        seed("Grim Soul — 500 Талеров",
                "500 Талеров для Grim Soul: Dark Survival RPG (iOS/Android). "
                        + "Пополнение через официальный магазин по аккаунту игры. Срок доставки — до 24 ч.",
                "Игровые валюты", 10_000,
                "Укажите email или ID аккаунта Grim Soul для пополнения.\n\n"
                        + "Введите email или ID:");

        seed("Clash Royale — 80 Gems",
                "80 Самоцветов для Clash Royale (iOS/Android). Пополнение через Supercell ID. "
                        + "Срок доставки — до 24 ч.",
                "Игровые валюты", 6_500,
                "Для пополнения Clash Royale нужен ваш Supercell ID.\n\n"
                        + "Введите email вашего Supercell ID:");

        seed("Brawl Stars — 80 Gems",
                "80 Самоцветов для Brawl Stars (iOS/Android). Пополнение через Supercell ID. "
                        + "Срок доставки — до 24 ч.",
                "Игровые валюты", 6_500,
                "Для пополнения Brawl Stars нужен ваш Supercell ID.\n\n"
                        + "Введите email вашего Supercell ID:");

        seed("Clash of Clans — 80 Gems",
                "80 Самоцветов для Clash of Clans (iOS/Android). Пополнение через Supercell ID. "
                        + "Срок доставки — до 24 ч.",
                "Игровые валюты", 6_500,
                "Для пополнения Clash of Clans нужен ваш Supercell ID.\n\n"
                        + "Введите email вашего Supercell ID:");

        seed("Mobile Legends — 86 Diamonds",
                "86 Алмазов для Mobile Legends: Bang Bang (iOS/Android). "
                        + "Пополнение по UID и серверу — вход в аккаунт не требуется. Срок доставки — до 24 ч.",
                "Игровые валюты", 9_000,
                "Для пополнения MLBB нужны UID и номер сервера.\n\n"
                        + "Где найти: откройте профиль в игре → UID и сервер указаны под никнеймом.\n\n"
                        + "Введите UID и сервер через пробел (пример: 123456789 2345):");

        seed("CS2 — Пополнение Steam 150 ₽",
                "Пополнение баланса Steam на ~150 ₽ для CS2 (PC). "
                        + "Зачисляется напрямую на ваш Steam-аккаунт по логину. Срок доставки — до 24 ч.",
                "Игровые валюты", 19_000,
                "Укажите ваш логин Steam (не email, не никнейм — именно логин для входа).\n\n"
                        + "Введите логин Steam:");
    }

    private void seed(String title, String description, String category, long priceCoins) {
        seed(title, description, category, priceCoins, null);
    }

    private void seed(String title, String description, String category, long priceCoins, String userDataPrompt) {
        rewardItemRepository.findByTitle(title).ifPresentOrElse(
                existing -> {
                    boolean changed = existing.getPriceCoins() != priceCoins;
                    if (changed) existing.setPriceCoins(priceCoins);
                    if (userDataPrompt != null && !userDataPrompt.equals(existing.getUserDataPrompt())) {
                        existing.setUserDataPrompt(userDataPrompt);
                        changed = true;
                    }
                    if (changed) {
                        rewardItemRepository.save(existing);
                        log.info("[RewardSeeder] Updated '{}': {} EXC", title, priceCoins);
                    }
                },
                () -> {
                    RewardItem item = new RewardItem();
                    item.setTitle(title);
                    item.setDescription(description);
                    item.setCategory(category);
                    item.setPriceCoins(priceCoins);
                    item.setUserDataPrompt(userDataPrompt);
                    item.setActive(true);
                    item.setCreatedAt(LocalDateTime.now());
                    rewardItemRepository.save(item);
                    log.info("[RewardSeeder] Created '{}': {} EXC", title, priceCoins);
                }
        );
    }
}
