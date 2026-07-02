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
                "Подарочные карты", 12_000, null, 1_000, "gift_card");

        seed("Gift Card Steam — 250 ₽",
                "Цифровой код пополнения Steam-кошелька на 250 рублей. "
                        + "Доставляется в Telegram после одобрения заявки администратором.",
                "Подарочные карты", 28_000, null, 5_000, "gift_card");

        seed("Gift Card Steam — 500 ₽",
                "Цифровой код пополнения Steam-кошелька на 500 рублей. "
                        + "Доставляется в Telegram после одобрения заявки администратором.",
                "Подарочные карты", 55_000, null, 15_000, "gift_card");

        seed("Gift Card PSN — 500 ₽",
                "Цифровой код пополнения PlayStation Network на 500 рублей. "
                        + "Доставляется в Telegram после одобрения заявки администратором.",
                "Подарочные карты", 55_000, null, 15_000, "gift_card");

        // ── EGC Эксклюзив ───────────────────────────────────────────────────────

        seed("Значок EGC — «Ветеран»",
                "Эксклюзивный цифровой значок в профиле EGC — «Ветеран клуба». "
                        + "Подтверждает участие в клубе на ранних этапах. Выдаётся вручную.",
                "EGC Эксклюзив", 20_000, null, 5_000, "badge_egc");

        seed("Футболка EGC — брендированная",
                "Брендированная футболка EXPERIENCE GAMING CLUB. "
                        + "Доставка по России. Уточнение размера и адреса — через поддержку.",
                "EGC Эксклюзив", 75_000, null, 35_000, "tshirt_egc");

        seed("EGC Council — статус на 1 месяц",
                "Доступ к закрытым Council-квестам с повышенными наградами на 30 дней. "
                        + "Число мест ограничено — статус активируется администратором вручную.",
                "EGC Эксклюзив", 100_000, null, 75_000, "council_egc");

        // ── Игровые валюты ───────────────────────────────────────────────────────

        seed("PUBG PC — 200 G-Coin",
                "200 G-Coin для PUBG PC. Доставляется в виде подарочного кода в Telegram. "
                        + "Код активируется в меню пополнения PUBG PC самостоятельно. Срок доставки — до 24 ч.",
                "Игровые валюты", 13_000, null, 1_000, "pubg_pc");

        seed("PUBG Mobile — 60 UC",
                "60 UC (Unknown Cash) для PUBG Mobile. Пополнение через midasbuy.com по вашему Player ID. "
                        + "Срок доставки — до 24 ч.",
                "Игровые валюты", 13_000,
                "Укажите ваш PUBG Mobile Player ID.\n\n"
                        + "Где найти: откройте профиль в игре → ваш ID указан под никнеймом.\n\n"
                        + "Введите Player ID:",
                1_000, "pubg_mobile");

        seed("EA FC 26 — 500 FC Points",
                "500 FC Points для EA FC 26 (PS5 или Xbox). Ключ активируется в PS Store или Xbox Store. "
                        + "Ключи для PS5 и Xbox — разные, укажите вашу платформу. Срок доставки — до 24 ч.",
                "Игровые валюты", 20_000,
                "Укажите вашу платформу для EA FC 26.\n\n"
                        + "Напишите: PS5 или Xbox",
                5_000, "ea_fc_26");

        seed("Grim Soul — 500 Талеров",
                "500 Талеров для Grim Soul: Dark Survival RPG (iOS/Android). "
                        + "Пополнение через официальный магазин по аккаунту игры. Срок доставки — до 24 ч.\n\n"
                        + "⚠️ Для зачисления потребуется временный доступ к вашему игровому аккаунту.",
                "Игровые валюты", 10_000,
                "⚠️ Важно: для зачисления валюты администратор временно войдёт в ваш игровой аккаунт.\n\n"
                        + "Оформляя заявку, вы соглашаетесь передать данные для входа через поддержку после одобрения.\n\n"
                        + "Укажите email или ID аккаунта Grim Soul:\n\n"
                        + "Введите email или ID:",
                1_000, "grim_soul");

        seed("Clash Royale — 80 Gems",
                "80 Самоцветов для Clash Royale (iOS/Android). Пополнение через Supercell ID. "
                        + "Срок доставки — до 24 ч.\n\n"
                        + "⚠️ Для зачисления потребуется временный доступ к вашему Supercell-аккаунту.",
                "Игровые валюты", 6_500,
                "⚠️ Важно: для зачисления самоцветов администратор временно войдёт в ваш Supercell-аккаунт.\n\n"
                        + "Оформляя заявку, вы соглашаетесь передать данные для входа через поддержку после одобрения.\n\n"
                        + "Укажите email вашего Supercell ID:\n\n"
                        + "Введите email:",
                0, "clash_royale");

        seed("Brawl Stars — 80 Gems",
                "80 Самоцветов для Brawl Stars (iOS/Android). Пополнение через Supercell ID. "
                        + "Срок доставки — до 24 ч.\n\n"
                        + "⚠️ Для зачисления потребуется временный доступ к вашему Supercell-аккаунту.",
                "Игровые валюты", 6_500,
                "⚠️ Важно: для зачисления самоцветов администратор временно войдёт в ваш Supercell-аккаунт.\n\n"
                        + "Оформляя заявку, вы соглашаетесь передать данные для входа через поддержку после одобрения.\n\n"
                        + "Укажите email вашего Supercell ID:\n\n"
                        + "Введите email:",
                0, "brawl_stars");

        seed("Clash of Clans — 80 Gems",
                "80 Самоцветов для Clash of Clans (iOS/Android). Пополнение через Supercell ID. "
                        + "Срок доставки — до 24 ч.\n\n"
                        + "⚠️ Для зачисления потребуется временный доступ к вашему Supercell-аккаунту.",
                "Игровые валюты", 6_500,
                "⚠️ Важно: для зачисления самоцветов администратор временно войдёт в ваш Supercell-аккаунт.\n\n"
                        + "Оформляя заявку, вы соглашаетесь передать данные для входа через поддержку после одобрения.\n\n"
                        + "Укажите email вашего Supercell ID:\n\n"
                        + "Введите email:",
                0, "clash_of_clans");

        seed("Mobile Legends — 86 Diamonds",
                "86 Алмазов для Mobile Legends: Bang Bang (iOS/Android). "
                        + "Пополнение по UID и серверу — вход в аккаунт не требуется. Срок доставки — до 24 ч.",
                "Игровые валюты", 9_000,
                "Для пополнения MLBB нужны UID и номер сервера.\n\n"
                        + "Где найти: откройте профиль в игре → UID и сервер указаны под никнеймом.\n\n"
                        + "Введите UID и сервер через пробел (пример: 123456789 2345):",
                1_000, "mobile_legends");

        seed("CS2 — Пополнение Steam 150 ₽",
                "Пополнение баланса Steam на ~150 ₽ для CS2 (PC). "
                        + "Зачисляется напрямую на ваш Steam-аккаунт по логину. Срок доставки — до 24 ч.",
                "Игровые валюты", 19_000,
                "Укажите ваш логин Steam (не email, не никнейм — именно логин для входа).\n\n"
                        + "Введите логин Steam:",
                5_000, "cs2");
    }

    private void seed(String title, String description, String category, long priceCoins,
                      String userDataPrompt, int minLevelXp, String purchaseGroup) {
        rewardItemRepository.findByTitle(title).ifPresentOrElse(
                existing -> {
                    boolean changed = existing.getPriceCoins() != priceCoins;
                    if (changed) existing.setPriceCoins(priceCoins);
                    if (userDataPrompt != null && !userDataPrompt.equals(existing.getUserDataPrompt())) {
                        existing.setUserDataPrompt(userDataPrompt);
                        changed = true;
                    }
                    if (existing.getMinLevelXp() != minLevelXp) {
                        existing.setMinLevelXp(minLevelXp);
                        changed = true;
                    }
                    if (!purchaseGroup.equals(existing.getPurchaseGroup())) {
                        existing.setPurchaseGroup(purchaseGroup);
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
                    item.setMinLevelXp(minLevelXp);
                    item.setPurchaseGroup(purchaseGroup);
                    item.setActive(true);
                    item.setCreatedAt(LocalDateTime.now());
                    rewardItemRepository.save(item);
                    log.info("[RewardSeeder] Created '{}': {} EXC", title, priceCoins);
                }
        );
    }
}
