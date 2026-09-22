package ru.gamebot.platform.config;

import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.gamebot.platform.bot.GamePlatformBot;
import ru.gamebot.platform.domain.model.AppUser;
import ru.gamebot.platform.domain.model.RewardItem;
import ru.gamebot.platform.domain.repository.AppUserRepository;
import ru.gamebot.platform.domain.repository.RewardItemRepository;
import ru.gamebot.platform.domain.repository.RewardRequestRepository;
import ru.gamebot.platform.service.GemPurchaseService;

@Slf4j
@Component
@Order(3)
@RequiredArgsConstructor
public class RewardSeeder implements CommandLineRunner {

    private final RewardItemRepository rewardItemRepository;
    private final RewardRequestRepository rewardRequestRepository;
    private final AppUserRepository appUserRepository;
    private final GamePlatformBot gamePlatformBot;
    private final GemPurchaseService gemPurchaseService;

    // EXC-цена игровой валюты (магазин наград) не должна быть дешевле доната (GRAM/Stars) за тот же
    // объём — иначе выгоднее выводить EXC в рубли и покупать донатом напрямую, что бьёт по Payout
    // Pool без всякой пользы. Целевой запас — 1.15x (принцип зафиксирован 2026-09-20, см.
    // project_economic_model). Сравнение по БАЗОВОЙ цене (100 EXC = 1₽) — эффект Health Ratio на
    // цену EXC-товара и на курс вывода взаимно сокращается, поэтому базовая цена — уже ₽-эквивалент,
    // не зависящий от текущего состояния фонда.
    private static final java.math.BigDecimal GEM_PRICING_TARGET_MARGIN = java.math.BigDecimal.valueOf(1.15);
    private static final java.math.BigDecimal EXC_TO_RUB_BASE_RATE = java.math.BigDecimal.valueOf(0.01);

    @Override
    @Transactional
    public void run(String... args) {

        // ── Удаление устаревших товаров ─────────────────────────────────────────
        deleteObsoleteItem("🔥 Анимированная огненная рамка аватара");
        deleteObsoleteItem("🔥 Рамка аватара");

        // ── Подарочные карты ────────────────────────────────────────────────────

        // Цены пересчитаны на паритет с курсом вывода EXC->руб (100 EXC = 1 ₽ при 100% Health Ratio,
        // см. RewardService.effectivePrice/HealthRatioService) — 2026-09-12, себестоимость = номинал карты.
        seed("Gift Card Steam — 100 ₽",
                "Цифровой код пополнения Steam-кошелька на 100 рублей. "
                        + "Доставляется в Telegram после одобрения заявки администратором. "
                        + "Код действует на аккаунты любого региона.",
                "Подарочные карты", 10_000, null, 1_000, "gift_card");

        seed("Gift Card Steam — 250 ₽",
                "Цифровой код пополнения Steam-кошелька на 250 рублей. "
                        + "Доставляется в Telegram после одобрения заявки администратором.",
                "Подарочные карты", 25_000, null, 5_000, "gift_card");

        seed("Gift Card Steam — 500 ₽",
                "Цифровой код пополнения Steam-кошелька на 500 рублей. "
                        + "Доставляется в Telegram после одобрения заявки администратором.",
                "Подарочные карты", 50_000, null, 15_000, "gift_card");

        seed("Gift Card PSN — 500 ₽",
                "Цифровой код пополнения PlayStation Network на 500 рублей. "
                        + "Доставляется в Telegram после одобрения заявки администратором.",
                "Подарочные карты", 50_000, null, 15_000, "gift_card");

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

        // Цены пересчитаны на паритет с курсом вывода (себестоимость закупки × 100) — 2026-09-12.
        seed("PUBG PC - G-Coin 100",
                "Пополнение 100 G-Coin на аккаунт PUBG PC. Доставляется по электронной почте. Срок доставки — до 24 ч.",
                "PUBG PC", 8_400, null, 1_000, "pubg_pc");

        seed("PUBG PC - G-Coin 500",
                "Пополнение 500 G-Coin на аккаунт PUBG PC. Доставляется по электронной почте. Срок доставки — до 24 ч.",
                "PUBG PC", 42_000, null, 15_000, "pubg_pc");

        seed("PUBG PC - G-Coin 1000",
                "Пополнение 1000 G-Coin на аккаунт PUBG PC. Доставляется по электронной почте. Срок доставки — до 24 ч.",
                "PUBG PC", 85_300, null, 75_000, "pubg_pc");

        // Deactivate old PUBG Mobile entry
        rewardItemRepository.findByTitle("PUBG Mobile — 60 UC").ifPresent(old -> {
            if (old.isActive()) { old.setActive(false); rewardItemRepository.save(old); }
        });

        String pubgMobilePrompt = "Укажите ваш PUBG Mobile Player ID.\n\n"
                + "Где найти: откройте профиль в игре → ваш ID указан под никнеймом.\n\n"
                + "Введите Player ID:";

        seed("PUBG Mobile - UC 120",
                "Пополнение 120 UC на аккаунт PUBG Mobile. Зачисляется по Player ID без входа в аккаунт. Срок доставки — до 24 ч.",
                "PUBG Mobile", 16_800, pubgMobilePrompt, 1_000, "pubg_mobile");

        seed("PUBG Mobile - UC 240",
                "Пополнение 240 UC на аккаунт PUBG Mobile. Зачисляется по Player ID без входа в аккаунт. Срок доставки — до 24 ч.",
                "PUBG Mobile", 33_600, pubgMobilePrompt, 5_000, "pubg_mobile");

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

        // Grim Soul убран из магазина целиком по запросу пользователя (2026-09-22) — не удаляем
        // строки (сломало бы историю уже одобренных заявок на эти товары), только деактивируем,
        // тот же паттерн, что у EA FC 26/Clash Royale 80 Gems выше. seed() ниже не трогает active
        // у существующей записи, так что оставлять эти вызовы закомментированными безопасно —
        // они не переактивируют товар обратно.
        for (String title : List.of("Grim Soul - Талеры 35", "Grim Soul - Талеры 150")) {
            rewardItemRepository.findByTitle(title).ifPresent(item -> {
                if (item.isActive()) { item.setActive(false); rewardItemRepository.save(item); }
            });
        }

        rewardItemRepository.findByTitle("Clash Royale — 80 Gems").ifPresent(old -> {
            if (old.isActive()) { old.setActive(false); rewardItemRepository.save(old); }
        });

        String crPrompt = "Введи эл. почту, к которой имеешь постоянный доступ. "
                + "На этот адрес придет письмо с ссылкой на активацию твоей покупки.\n\n"
                + "Не забудь проверить папку «Спам»\n\n"
                + "Введите email:";

        // Цены подняты 2026-09-20 (тот же повод, что у Brawl Stars выше — смена поставщика доната
        // donatov.net -> Купикод, розничные цены заметно выросли, старые EXC-цены оказались бы дешевле
        // доната). Новые цены держат запас ~1.2x против актуального доната Купикод.
        seed("Clash Royale - Gems 160",
                "Пополнение 160 гемов на аккаунт Clash Royale. Доставка на email. Срок доставки — до 24 ч.",
                "Clash Royale", 33_600, crPrompt, 1_000, "clash_royale");

        seed("Clash Royale - Gems 500",
                "Пополнение 500 гемов на аккаунт Clash Royale. Доставка на email. Срок доставки — до 24 ч.",
                "Clash Royale", 77_700, crPrompt, 5_000, "clash_royale");

        seed("Clash Royale - Gems 1200",
                "Пополнение 1200 гемов на аккаунт Clash Royale. Доставка на email. Срок доставки — до 24 ч.",
                "Clash Royale", 151_600, crPrompt, 15_000, "clash_royale");

        rewardItemRepository.findByTitle("Brawl Stars — 80 Gems").ifPresent(old -> {
            if (old.isActive()) { old.setActive(false); rewardItemRepository.save(old); }
        });

        String brawlPrompt = "Введи эл. почту, к которой имеешь постоянный доступ. "
                + "На этот адрес придет письмо с ссылкой на активацию твоей покупки.\n\n"
                + "Не забудь проверить папку «Спам»\n\n"
                + "Введите email:";

        // Цены подняты 2026-09-20 (смена поставщика доната donatov.net -> Купикод, см.
        // GemPurchaseService javadoc) — розничные цены Купикод заметно выше снимка donatov.net от
        // 18.09, из-за чего старые EXC-цены оказались бы ДЕШЕВЛЕ доната за те же гемы (нарушение
        // принципа минимум 1.15x запаса, см. RewardSeeder.checkGemPricingMargin/project_economic_model).
        // Новые цены держат запас ~1.2x против актуального доната.
        seed("Brawl Stars - Gems 30",
                "Пополнение 30 гемов на аккаунт Brawl Stars. Доставка на email. Срок доставки — до 24 ч.",
                "Brawl Stars", 35_900, brawlPrompt, 1_000, "brawl_stars");

        seed("Brawl Stars - Gems 60",
                "Пополнение 60 гемов на аккаунт Brawl Stars. Доставка на email. Срок доставки — до 24 ч.",
                "Brawl Stars", 67_800, brawlPrompt, 5_000, "brawl_stars");

        seed("Brawl Stars - Gems 110",
                "Пополнение 110 гемов на аккаунт Brawl Stars. Доставка на email. Срок доставки — до 24 ч.",
                "Brawl Stars", 114_200, brawlPrompt, 15_000, "brawl_stars");

        rewardItemRepository.findByTitle("Clash of Clans — 80 Gems").ifPresent(old -> {
            if (old.isActive()) { old.setActive(false); rewardItemRepository.save(old); }
        });

        String cocPrompt = "Укажите email вашего Supercell ID для зачисления гемов.\n\n"
                + "Введите email:";

        // Цены подняты 2026-09-20 (тот же повод, что у Brawl Stars/Clash Royale выше — смена
        // поставщика доната donatov.net -> Купикод). Новые цены держат запас ~1.2x против доната.
        seed("Clash of Clans - Gems 80",
                "Пополнение 80 гемов на аккаунт Clash of Clans. Доставка через Supercell ID. Срок доставки — до 24 ч.",
                "Clash of Clans", 18_800, cocPrompt, 1_000, "clash_of_clans");

        // Количество пачки уменьшено 260 -> 160 гемов (2026-09-12) — старый тайтл деактивируем,
        // чтобы не остался дублирующим активным товаром по старой цене/объёму.
        rewardItemRepository.findByTitle("Clash of Clans - Gems 260").ifPresent(old -> {
            if (old.isActive()) { old.setActive(false); rewardItemRepository.save(old); }
        });

        seed("Clash of Clans - Gems 160",
                "Пополнение 160 гемов на аккаунт Clash of Clans. Доставка через Supercell ID. Срок доставки — до 24 ч.",
                "Clash of Clans", 34_500, cocPrompt, 5_000, "clash_of_clans");

        seed("Clash of Clans - Gems 500",
                "Пополнение 500 гемов на аккаунт Clash of Clans. Доставка через Supercell ID. Срок доставки — до 24 ч.",
                "Clash of Clans", 81_400, cocPrompt, 15_000, "clash_of_clans");

        rewardItemRepository.findByTitle("Mobile Legends — 86 Diamonds").ifPresent(old -> {
            if (old.isActive()) { old.setActive(false); rewardItemRepository.save(old); }
        });

        String mlPrompt = "Для пополнения нужны ID аккаунта и Zone ID.\n\n"
                + "Где найти: откройте профиль в игре → ID и Zone ID указаны под никнеймом.\n\n"
                + "Введите ID аккаунта и Zone ID через пробел (пример: 123456789 2345):";

        seed("Mobile Legends - Diamonds 35",
                "Пополнение 35 Diamonds на аккаунт Mobile Legends. Зачисляется по ID аккаунта и Zone ID без входа. Срок доставки — до 24 ч.",
                "Mobile Legends", 8_000, mlPrompt, 1_000, "mobile_legends");

        seed("Mobile Legends - Diamonds 55",
                "Пополнение 55 Diamonds на аккаунт Mobile Legends. Зачисляется по ID аккаунта и Zone ID без входа. Срок доставки — до 24 ч.",
                "Mobile Legends", 12_500, mlPrompt, 1_000, "mobile_legends");

        seed("Mobile Legends - Diamonds 275",
                "Пополнение 275 Diamonds на аккаунт Mobile Legends. Зачисляется по ID аккаунта и Zone ID без входа. Срок доставки — до 24 ч.",
                "Mobile Legends", 65_000, mlPrompt, 15_000, "mobile_legends");

        // Deactivate old CS2 entry created with em-dash title
        rewardItemRepository.findByTitle("CS2 — Пополнение Steam 150 ₽").ifPresent(old -> {
            if (old.isActive()) { old.setActive(false); rewardItemRepository.save(old); }
        });

        String cs2Prompt = "Укажите ваш логин Steam (не email, не никнейм — именно логин для входа).\n\n"
                + "Введите логин Steam:";

        seed("CS2 - Пополнение Steam 150 ₽",
                "Пополнение баланса Steam на ~150 ₽ для CS2 (PC). "
                        + "Зачисляется напрямую на ваш Steam-аккаунт по логину. Срок доставки — до 24 ч.",
                "CS2", 15_000, cs2Prompt, 5_000, "cs2");

        seed("CS2 - Пополнение Steam 250 ₽",
                "Пополнение баланса Steam на ~250 ₽ для CS2 (PC). "
                        + "Зачисляется напрямую на ваш Steam-аккаунт по логину. Срок доставки — до 24 ч.",
                "CS2", 25_000, cs2Prompt, 5_000, "cs2");

        seed("CS2 - Пополнение Steam 500 ₽",
                "Пополнение баланса Steam на ~500 ₽ для CS2 (PC). "
                        + "Зачисляется напрямую на ваш Steam-аккаунт по логину. Срок доставки — до 24 ч.",
                "CS2", 50_000, cs2Prompt, 15_000, "cs2");

        // ── Вывод в Telegram Stars (2026-09-13) ───────────────────────────────────
        // Альтернативный способ вывода наряду с рублями/TON — исполняется ВРУЧНУЮ администратором
        // через стороннего реселлера звёзд по юзернейму получателя (без переписки, без пополнения
        // баланса самого бота — прямого API для этого у Telegram нет). Цены — паритет с курсом
        // вывода (реальная себестоимость в рублях × 100 при 100% Health Ratio), тот же принцип,
        // что и у подарочных карт/игровой валюты. minLevelXp=0 везде — это вывод, не товар магазина,
        // рангового гейта тут быть не должно, только общий месячный лимит трат (как у рублей/TON).
        String starsPrompt = "Введите ваш Telegram username (без @) — на него будут отправлены звёзды.\n\n"
                + "Убедитесь, что юзернейм указан верно — с ним свяжутся при выплате:";

        seed("Telegram Stars - 50 ⭐", "Вывод EXC в 50 звёзд Telegram. Отправляется вручную администратором по юзернейму. Срок — до 24 ч.",
                "Вывод", 7_900, starsPrompt, 0, "telegram_stars");
        seed("Telegram Stars - 75 ⭐", "Вывод EXC в 75 звёзд Telegram. Отправляется вручную администратором по юзернейму. Срок — до 24 ч.",
                "Вывод", 11_800, starsPrompt, 0, "telegram_stars");
        seed("Telegram Stars - 100 ⭐", "Вывод EXC в 100 звёзд Telegram. Отправляется вручную администратором по юзернейму. Срок — до 24 ч.",
                "Вывод", 15_600, starsPrompt, 0, "telegram_stars");
        seed("Telegram Stars - 150 ⭐", "Вывод EXC в 150 звёзд Telegram. Отправляется вручную администратором по юзернейму. Срок — до 24 ч.",
                "Вывод", 23_300, starsPrompt, 0, "telegram_stars");
        seed("Telegram Stars - 250 ⭐", "Вывод EXC в 250 звёзд Telegram. Отправляется вручную администратором по юзернейму. Срок — до 24 ч.",
                "Вывод", 38_900, starsPrompt, 0, "telegram_stars");
        seed("Telegram Stars - 350 ⭐", "Вывод EXC в 350 звёзд Telegram. Отправляется вручную администратором по юзернейму. Срок — до 24 ч.",
                "Вывод", 54_900, starsPrompt, 0, "telegram_stars");
        seed("Telegram Stars - 500 ⭐", "Вывод EXC в 500 звёзд Telegram. Отправляется вручную администратором по юзернейму. Срок — до 24 ч.",
                "Вывод", 77_900, starsPrompt, 0, "telegram_stars");
        seed("Telegram Stars - 750 ⭐", "Вывод EXC в 750 звёзд Telegram. Отправляется вручную администратором по юзернейму. Срок — до 24 ч.",
                "Вывод", 116_900, starsPrompt, 0, "telegram_stars");
        seed("Telegram Stars - 1000 ⭐", "Вывод EXC в 1000 звёзд Telegram. Отправляется вручную администратором по юзернейму. Срок — до 24 ч.",
                "Вывод", 155_900, starsPrompt, 0, "telegram_stars");
        seed("Telegram Stars - 1500 ⭐", "Вывод EXC в 1500 звёзд Telegram. Отправляется вручную администратором по юзернейму. Срок — до 24 ч.",
                "Вывод", 233_900, starsPrompt, 0, "telegram_stars");
        seed("Telegram Stars - 2500 ⭐", "Вывод EXC в 2500 звёзд Telegram. Отправляется вручную администратором по юзернейму. Срок — до 24 ч.",
                "Вывод", 388_900, starsPrompt, 0, "telegram_stars");
        seed("Telegram Stars - 5000 ⭐", "Вывод EXC в 5000 звёзд Telegram. Отправляется вручную администратором по юзернейму. Срок — до 24 ч.",
                "Вывод", 776_900, starsPrompt, 0, "telegram_stars");
        seed("Telegram Stars - 10000 ⭐", "Вывод EXC в 10000 звёзд Telegram. Отправляется вручную администратором по юзернейму. Срок — до 24 ч.",
                "Вывод", 1_553_900, starsPrompt, 0, "telegram_stars");

        // ── Временно закрыт доступ игрокам: CS2, Mobile Legends («скоро откроется») ──
        // Товары не удаляются, только помечаются «скоро» — чтобы вернуть доступ, достаточно убрать этот блок.
        markComingSoon("cs2");
        markComingSoon("mobile_legends");

        // ── Кастомизация: рамки аватара (применяются мгновенно, без одобрения) ────

        seedAvatarFrame("🔥 Огненная рамка аватара",
                "Огненная рамка аватара в профиле мини-аппа. Применяется сразу после покупки.",
                3_000, "#ef4444", "fire");

        seedAvatarFrame("❄️ Ледяная рамка аватара",
                "Ледяная рамка вокруг аватара в профиле мини-аппа. Применяется сразу после покупки.",
                3_000, "#38bdf8", "ice");

        seedAvatarFrame("💜 Фиолетовая рамка аватара",
                "Фирменная фиолетовая рамка вокруг аватара в профиле мини-аппа. Применяется сразу после покупки.",
                3_000, "#a855f7", "purple");

        seedAvatarFrame("👑 Золотая рамка аватара",
                "Премиальная золотая рамка вокруг аватара в профиле мини-аппа. Применяется сразу после покупки.",
                5_000, "#fbbf24", "gold");

        // Одноразовый backfill: игрокам, купившим рамку ДО появления картинки (была только заливка
        // цветом), проставляем avatarFrameImage — иначе картинка появляется только при следующей покупке.
        backfillAvatarFrameImage("#ef4444", "fire");
        backfillAvatarFrameImage("#38bdf8", "ice");
        backfillAvatarFrameImage("#a855f7", "purple");
        backfillAvatarFrameImage("#fbbf24", "gold");

        checkGemPricingMargin();
    }

    /** См. комментарий у GEM_PRICING_TARGET_MARGIN. Сравнивает каждый активный EXC-товар в магазине
     *  наград (для ВСЕХ игр, где настроен донат — см. GemPurchaseService.donationGameKeys(), не только
     *  Brawl Stars) с донат-пакетом того же объёма гемов, сопоставление по числу гемов в конце title
     *  (см. RewardSeeder.seed("<Игра> - Gems N", ...)). Если запас ниже 1.0x (EXC-товар ДЕШЕВЛЕ доната)
     *  — это уже нарушение принципа, не только "тоньше целевого". */
    private void checkGemPricingMargin() {
        for (String gameKey : GemPurchaseService.donationGameKeys()) {
            List<RewardItem> items = rewardItemRepository.findAllByActiveTrueAndPurchaseGroupOrderByPriceCoinsAsc(gameKey);
            for (RewardItem item : items) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)$").matcher(item.getTitle());
                if (!m.find()) continue;
                String packageKey = m.group(1);
                gemPurchaseService.findPackage(gameKey, packageKey).ifPresent(pkg -> {
                    java.math.BigDecimal impliedRub = java.math.BigDecimal.valueOf(item.getPriceCoins()).multiply(EXC_TO_RUB_BASE_RATE);
                    java.math.BigDecimal donateRub = java.math.BigDecimal.valueOf(pkg.priceRub());
                    if (impliedRub.compareTo(donateRub) < 0) {
                        String warning = "🚨 <b>Ценовой перекос в магазине наград</b>\n\n"
                                + "«" + item.getTitle() + "» стоит " + item.getPriceCoins() + " EXC (≈"
                                + impliedRub.setScale(0, java.math.RoundingMode.HALF_UP) + "₽ по базовому курсу 100 EXC=1₽), "
                                + "а донат (GRAM/Stars) за " + pkg.gems() + " гемов стоит всего " + pkg.priceRub() + "₽.\n\n"
                                + "EXC-товар ДЕШЕВЛЕ доната — выгоднее выводить EXC в рубли и покупать напрямую. "
                                + "Нужно поднять цену минимум до " + donateRub.multiply(GEM_PRICING_TARGET_MARGIN)
                                        .setScale(0, java.math.RoundingMode.HALF_UP) + "₽-эквивалента.";
                        log.warn("[RewardSeeder] {}", warning.replaceAll("<[^>]+>", ""));
                        gamePlatformBot.notifyAdminsPricingImbalance(warning);
                    } else if (impliedRub.compareTo(donateRub.multiply(GEM_PRICING_TARGET_MARGIN)) < 0) {
                        log.info("[RewardSeeder] Ценовой запас у '{}' ниже целевого 1.15x (сейчас {}₽ vs донат {}₽) — не критично, но стоит пересмотреть при следующей ревизии цен.",
                                item.getTitle(), impliedRub.setScale(0, java.math.RoundingMode.HALF_UP), donateRub);
                    }
                });
            }
        }
    }

    private void backfillAvatarFrameImage(String frameColor, String frameImage) {
        List<AppUser> users = appUserRepository.findAllByAvatarFrameColorAndAvatarFrameImageIsNull(frameColor);
        if (users.isEmpty()) return;
        for (AppUser user : users) {
            user.setAvatarFrameImage(frameImage);
        }
        appUserRepository.saveAll(users);
        log.info("[RewardSeeder] Backfilled avatarFrameImage='{}' for {} user(s) with frame color {}", frameImage, users.size(), frameColor);
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
                    if (category != null && !category.equals(existing.getCategory())) {
                        existing.setCategory(category);
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

    private void deleteObsoleteItem(String title) {
        rewardItemRepository.findByTitle(title).ifPresent(item -> {
            rewardRequestRepository.deleteAllByRewardItem(item);
            rewardItemRepository.delete(item);
            log.info("[RewardSeeder] Deleted obsolete item '{}'", title);
        });
    }
}
