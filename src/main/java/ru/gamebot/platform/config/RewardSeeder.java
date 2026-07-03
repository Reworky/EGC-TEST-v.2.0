package ru.gamebot.platform.config;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.bot.GamePlatformBot;
import ru.gamebot.platform.domain.model.RewardItem;
import ru.gamebot.platform.domain.repository.RewardItemRepository;

@Slf4j
@Component
@Order(3)
@RequiredArgsConstructor
public class RewardSeeder implements CommandLineRunner {

    private final RewardItemRepository rewardItemRepository;
    private final GamePlatformBot gamePlatformBot;

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

        // Deactivate old PUBG PC entry
        rewardItemRepository.findByTitle("PUBG PC — 200 G-Coin").ifPresent(old -> {
            if (old.isActive()) { old.setActive(false); rewardItemRepository.save(old); }
        });

        seed("PUBG PC - G-Coin 100",
                "Пополнение 100 G-Coin на аккаунт PUBG PC. Доставляется по электронной почте. Срок доставки — до 24 ч.",
                "Игровые валюты", 10_000, null, 1_000, "pubg_pc");

        seed("PUBG PC - G-Coin 500",
                "Пополнение 500 G-Coin на аккаунт PUBG PC. Доставляется по электронной почте. Срок доставки — до 24 ч.",
                "Игровые валюты", 51_500, null, 15_000, "pubg_pc");

        seed("PUBG PC - G-Coin 1000",
                "Пополнение 1000 G-Coin на аккаунт PUBG PC. Доставляется по электронной почте. Срок доставки — до 24 ч.",
                "Игровые валюты", 108_000, null, 75_000, "pubg_pc");

        // Deactivate old PUBG Mobile entry
        rewardItemRepository.findByTitle("PUBG Mobile — 60 UC").ifPresent(old -> {
            if (old.isActive()) { old.setActive(false); rewardItemRepository.save(old); }
        });

        String pubgMobilePrompt = "Укажите ваш PUBG Mobile Player ID.\n\n"
                + "Где найти: откройте профиль в игре → ваш ID указан под никнеймом.\n\n"
                + "Введите Player ID:";

        seed("PUBG Mobile - UC 120",
                "Пополнение 120 UC на аккаунт PUBG Mobile. Зачисляется по Player ID без входа в аккаунт. Срок доставки — до 24 ч.",
                "Игровые валюты", 21_500, pubgMobilePrompt, 1_000, "pubg_mobile");

        seed("PUBG Mobile - UC 240",
                "Пополнение 240 UC на аккаунт PUBG Mobile. Зачисляется по Player ID без входа в аккаунт. Срок доставки — до 24 ч.",
                "Игровые валюты", 42_000, pubgMobilePrompt, 5_000, "pubg_mobile");

        // EA FC 26 — coming soon (visible but not purchasable)
        rewardItemRepository.findByTitle("EA FC 26 — 500 FC Points").ifPresent(item -> {
            boolean changed = false;
            if (item.isActive()) { item.setActive(false); changed = true; }
            if (!item.isComingSoon()) { item.setComingSoon(true); changed = true; }
            if (changed) rewardItemRepository.save(item);
        });

        seed("Grim Soul — 500 Талеров",
                "500 Талеров для Grim Soul: Dark Survival RPG (iOS/Android). "
                        + "Пополнение через официальный магазин по аккаунту игры. Срок доставки — до 24 ч.",
                "Игровые валюты", 10_000,
                "⚠️ Важно: для зачисления валюты администратор временно войдёт в ваш игровой аккаунт.\n\n"
                        + "Оформляя заявку, вы соглашаетесь передать данные для входа через поддержку после одобрения.\n\n"
                        + "Укажите email или ID аккаунта Grim Soul:\n\n"
                        + "Введите email или ID:",
                1_000, "grim_soul");

        seed("Clash Royale — 80 Gems",
                "80 Самоцветов для Clash Royale (iOS/Android). Пополнение через Supercell ID. "
                        + "Срок доставки — до 24 ч.",
                "Игровые валюты", 6_500,
                "⚠️ Важно: для зачисления самоцветов администратор временно войдёт в ваш Supercell-аккаунт.\n\n"
                        + "Оформляя заявку, вы соглашаетесь передать данные для входа через поддержку после одобрения.\n\n"
                        + "Укажите email вашего Supercell ID:\n\n"
                        + "Введите email:",
                0, "clash_royale");

        seed("Brawl Stars — 80 Gems",
                "80 Самоцветов для Brawl Stars (iOS/Android). Пополнение через Supercell ID. "
                        + "Срок доставки — до 24 ч.",
                "Игровые валюты", 6_500,
                "⚠️ Важно: для зачисления самоцветов администратор временно войдёт в ваш Supercell-аккаунт.\n\n"
                        + "Оформляя заявку, вы соглашаетесь передать данные для входа через поддержку после одобрения.\n\n"
                        + "Укажите email вашего Supercell ID:\n\n"
                        + "Введите email:",
                0, "brawl_stars");

        seed("Clash of Clans — 80 Gems",
                "80 Самоцветов для Clash of Clans (iOS/Android). Пополнение через Supercell ID. "
                        + "Срок доставки — до 24 ч.",
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

        // Deactivate old CS2 entry created with em-dash title
        rewardItemRepository.findByTitle("CS2 — Пополнение Steam 150 ₽").ifPresent(old -> {
            if (old.isActive()) { old.setActive(false); rewardItemRepository.save(old); }
        });

        String cs2Prompt = "Укажите ваш логин Steam (не email, не никнейм — именно логин для входа).\n\n"
                + "Введите логин Steam:";

        seed("CS2 - Пополнение Steam 150 ₽",
                "Пополнение баланса Steam на ~150 ₽ для CS2 (PC). "
                        + "Зачисляется напрямую на ваш Steam-аккаунт по логину. Срок доставки — до 24 ч.",
                "Игровые валюты", 19_000, cs2Prompt, 5_000, "cs2");

        seed("CS2 - Пополнение Steam 250 ₽",
                "Пополнение баланса Steam на ~250 ₽ для CS2 (PC). "
                        + "Зачисляется напрямую на ваш Steam-аккаунт по логину. Срок доставки — до 24 ч.",
                "Игровые валюты", 32_000, cs2Prompt, 5_000, "cs2");

        seed("CS2 - Пополнение Steam 500 ₽",
                "Пополнение баланса Steam на ~500 ₽ для CS2 (PC). "
                        + "Зачисляется напрямую на ваш Steam-аккаунт по логину. Срок доставки — до 24 ч.",
                "Игровые валюты", 63_000, cs2Prompt, 15_000, "cs2");
    }

    private void seed(String title, String description, String category, long priceCoins,
                      String userDataPrompt, int minLevelXp, String purchaseGroup) {
        rewardItemRepository.findByTitle(title).ifPresentOrElse(
                existing -> {
                    boolean changed = existing.getPriceCoins() != priceCoins;
                    if (changed) existing.setPriceCoins(priceCoins);
                    if (description != null && !description.equals(existing.getDescription())) {
                        existing.setDescription(description);
                        changed = true;
                    }
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
                    gamePlatformBot.requestNewsApproval(
                            "🎁 Новый товар в магазине",
                            "В магазин наград добавлен <b>" + title + "</b> за " + priceCoins + " EXC. Загляни в раздел 🛍 Магазин!"
                    );
                }
        );
    }
}
