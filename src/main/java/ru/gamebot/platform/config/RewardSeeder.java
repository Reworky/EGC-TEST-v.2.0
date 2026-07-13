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

        rewardItemRepository.findByTitle("Grim Soul — 500 Талеров").ifPresent(old -> {
            if (old.isActive()) { old.setActive(false); rewardItemRepository.save(old); }
        });

        String grimSoulPrompt = "Укажите ваш ID аккаунта Grim Soul.\n\n"
                + "Где найти: откройте профиль в игре → ваш ID указан под именем.\n\n"
                + "Введите ID аккаунта:";

        seed("Grim Soul - Талеры 35",
                "Пополнение 35 Талеров на аккаунт Grim Soul. Зачисляется по ID аккаунта без входа. Срок доставки — до 24 ч.",
                "Игровые валюты", 20_000, grimSoulPrompt, 1_000, "grim_soul");

        seed("Grim Soul - Талеры 150",
                "Пополнение 150 Талеров на аккаунт Grim Soul. Зачисляется по ID аккаунта без входа. Срок доставки — до 24 ч.",
                "Игровые валюты", 81_500, grimSoulPrompt, 75_000, "grim_soul");

        rewardItemRepository.findByTitle("Clash Royale — 80 Gems").ifPresent(old -> {
            if (old.isActive()) { old.setActive(false); rewardItemRepository.save(old); }
        });

        String crPrompt = "Введи эл. почту, к которой имеешь постоянный доступ. "
                + "На этот адрес придет письмо с ссылкой на активацию твоей покупки.\n\n"
                + "Не забудь проверить папку «Спам»\n\n"
                + "Введите email:";

        seed("Clash Royale - Gems 160",
                "Пополнение 160 гемов на аккаунт Clash Royale. Доставка на email. Срок доставки — до 24 ч.",
                "Игровые валюты", 30_000, crPrompt, 1_000, "clash_royale");

        seed("Clash Royale - Gems 500",
                "Пополнение 500 гемов на аккаунт Clash Royale. Доставка на email. Срок доставки — до 24 ч.",
                "Игровые валюты", 72_000, crPrompt, 5_000, "clash_royale");

        seed("Clash Royale - Gems 1200",
                "Пополнение 1200 гемов на аккаунт Clash Royale. Доставка на email. Срок доставки — до 24 ч.",
                "Игровые валюты", 141_000, crPrompt, 15_000, "clash_royale");

        rewardItemRepository.findByTitle("Brawl Stars — 80 Gems").ifPresent(old -> {
            if (old.isActive()) { old.setActive(false); rewardItemRepository.save(old); }
        });

        String brawlPrompt = "Введи эл. почту, к которой имеешь постоянный доступ. "
                + "На этот адрес придет письмо с ссылкой на активацию твоей покупки.\n\n"
                + "Не забудь проверить папку «Спам»\n\n"
                + "Введите email:";

        seed("Brawl Stars - Gems 30",
                "Пополнение 30 гемов на аккаунт Brawl Stars. Доставка на email. Срок доставки — до 24 ч.",
                "Игровые валюты", 30_000, brawlPrompt, 1_000, "brawl_stars");

        seed("Brawl Stars - Gems 60",
                "Пополнение 60 гемов на аккаунт Brawl Stars. Доставка на email. Срок доставки — до 24 ч.",
                "Игровые валюты", 58_000, brawlPrompt, 5_000, "brawl_stars");

        seed("Brawl Stars - Gems 110",
                "Пополнение 110 гемов на аккаунт Brawl Stars. Доставка на email. Срок доставки — до 24 ч.",
                "Игровые валюты", 98_000, brawlPrompt, 15_000, "brawl_stars");

        rewardItemRepository.findByTitle("Clash of Clans — 80 Gems").ifPresent(old -> {
            if (old.isActive()) { old.setActive(false); rewardItemRepository.save(old); }
        });

        String cocPrompt = "Укажите email вашего Supercell ID для зачисления гемов.\n\n"
                + "Введите email:";

        seed("Clash of Clans - Gems 80",
                "Пополнение 80 гемов на аккаунт Clash of Clans. Доставка через Supercell ID. Срок доставки — до 24 ч.",
                "Игровые валюты", 16_000, cocPrompt, 1_000, "clash_of_clans");

        seed("Clash of Clans - Gems 260",
                "Пополнение 260 гемов на аккаунт Clash of Clans. Доставка через Supercell ID. Срок доставки — до 24 ч.",
                "Игровые валюты", 29_500, cocPrompt, 5_000, "clash_of_clans");

        seed("Clash of Clans - Gems 500",
                "Пополнение 500 гемов на аккаунт Clash of Clans. Доставка через Supercell ID. Срок доставки — до 24 ч.",
                "Игровые валюты", 72_500, cocPrompt, 15_000, "clash_of_clans");

        rewardItemRepository.findByTitle("Mobile Legends — 86 Diamonds").ifPresent(old -> {
            if (old.isActive()) { old.setActive(false); rewardItemRepository.save(old); }
        });

        String mlPrompt = "Для пополнения нужны ID аккаунта и Zone ID.\n\n"
                + "Где найти: откройте профиль в игре → ID и Zone ID указаны под никнеймом.\n\n"
                + "Введите ID аккаунта и Zone ID через пробел (пример: 123456789 2345):";

        seed("Mobile Legends - Diamonds 35",
                "Пополнение 35 Diamonds на аккаунт Mobile Legends. Зачисляется по ID аккаунта и Zone ID без входа. Срок доставки — до 24 ч.",
                "Игровые валюты", 8_000, mlPrompt, 1_000, "mobile_legends");

        seed("Mobile Legends - Diamonds 55",
                "Пополнение 55 Diamonds на аккаунт Mobile Legends. Зачисляется по ID аккаунта и Zone ID без входа. Срок доставки — до 24 ч.",
                "Игровые валюты", 12_500, mlPrompt, 1_000, "mobile_legends");

        seed("Mobile Legends - Diamonds 275",
                "Пополнение 275 Diamonds на аккаунт Mobile Legends. Зачисляется по ID аккаунта и Zone ID без входа. Срок доставки — до 24 ч.",
                "Игровые валюты", 65_000, mlPrompt, 15_000, "mobile_legends");

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

        // ── Временно закрыт доступ игрокам: CS2, Mobile Legends («скоро откроется») ──
        // Товары не удаляются, только помечаются «скоро» — чтобы вернуть доступ, достаточно убрать этот блок.
        markComingSoon("cs2");
        markComingSoon("mobile_legends");

        // ── Кастомизация: рамки аватара (применяются мгновенно, без одобрения) ────

        seedAvatarFrame("🔥 Огненная рамка аватара",
                "Огненная рамка вокруг аватара в профиле мини-аппа. Применяется сразу после покупки.",
                3_000, "#ef4444", "fire");

        seedAvatarFrame("❄️ Ледяная рамка аватара",
                "Ледяная рамка вокруг аватара в профиле мини-аппа. Применяется сразу после покупки.",
                3_000, "#38bdf8", null);

        seedAvatarFrame("💜 Фиолетовая рамка аватара",
                "Фирменная фиолетовая рамка вокруг аватара в профиле мини-аппа. Применяется сразу после покупки.",
                3_000, "#a855f7", null);

        seedAvatarFrame("👑 Золотая рамка аватара",
                "Премиальная золотая рамка вокруг аватара в профиле мини-аппа. Применяется сразу после покупки.",
                5_000, "#fbbf24", null);
    }

    private void seedAvatarFrame(String title, String description, long priceCoins, String frameColor, String frameImage) {
        rewardItemRepository.findByTitle(title).ifPresentOrElse(
                existing -> {
                    boolean changed = false;
                    if (existing.getPriceCoins() != priceCoins) {
                        existing.setPriceCoins(priceCoins);
                        changed = true;
                    }
                    if (!frameColor.equals(existing.getAvatarFrameColor())) {
                        existing.setAvatarFrameColor(frameColor);
                        changed = true;
                    }
                    if (!java.util.Objects.equals(frameImage, existing.getAvatarFrameImage())) {
                        existing.setAvatarFrameImage(frameImage);
                        changed = true;
                    }
                    if (changed) {
                        rewardItemRepository.save(existing);
                        log.info("[RewardSeeder] Updated avatar frame '{}': {} EXC", title, priceCoins);
                    }
                },
                () -> {
                    RewardItem item = new RewardItem();
                    item.setTitle(title);
                    item.setDescription(description);
                    item.setCategory("Кастомизация");
                    item.setPriceCoins(priceCoins);
                    item.setMinLevelXp(0);
                    item.setPurchaseGroup("avatar_frame");
                    item.setAvatarFrameColor(frameColor);
                    item.setAvatarFrameImage(frameImage);
                    item.setActive(true);
                    item.setCreatedAt(LocalDateTime.now());
                    rewardItemRepository.save(item);
                    log.info("[RewardSeeder] Created avatar frame '{}': {} EXC", title, priceCoins);
                    gamePlatformBot.requestNewsApproval(
                            "🎁 Новый товар в магазине",
                            "В магазин наград добавлена <b>" + title + "</b> за " + priceCoins + " EXC — новая кастомизация профиля! Загляни в раздел 🛍 Магазин."
                    );
                }
        );
    }

    private void markComingSoon(String purchaseGroup) {
        rewardItemRepository.findAll().stream()
                .filter(item -> purchaseGroup.equals(item.getPurchaseGroup()) && (item.isActive() || !item.isComingSoon()))
                .forEach(item -> {
                    item.setActive(false);
                    item.setComingSoon(true);
                    rewardItemRepository.save(item);
                    log.info("[RewardSeeder] Closed access (coming soon): '{}' [{}]", item.getTitle(), purchaseGroup);
                });
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
