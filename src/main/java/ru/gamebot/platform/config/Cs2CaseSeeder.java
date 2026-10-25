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
import ru.gamebot.platform.service.Cs2CaseCatalog;

/**
 * Товары-кейсы CS2 из cs2_cases.json. Только создаёт и обновляет (удалений нет), на объявление в новостях не влияет.
 * Активность товаров = CS2_CASES_ENABLED: выключено - товары скрыты у игроков, включено - продаются.
 */
@Slf4j
@Component
@Order(4)
@RequiredArgsConstructor
public class Cs2CaseSeeder implements CommandLineRunner {

    private static final String TRADE_PROMPT = "Укажите вашу ссылку обмена Steam (Trade URL).\n\n"
            + "Где взять: Steam → Инвентарь → Предложения обмена → «Кто может отправлять мне предложения обмена?» → ссылка.\n"
            + "Профиль и инвентарь должны быть открытыми, а обмен на вашем аккаунте не ограничен.\n\nВведите ссылку:";

    private final RewardItemRepository rewardItemRepository;
    private final Cs2CaseCatalog catalog;

    @Override
    @Transactional
    public void run(String... args) {
        for (Cs2CaseCatalog.CaseDef c : catalog.all()) {
            String description = c.displayName() + (c.withKey() ? " с ключом" : " (без ключа, ключ для открытия покупается отдельно в Steam)") + ". Внутри " + c.skins().size()
                    + " скинов: " + String.join(", ", c.skins()) + ". Выдаём обменом в Steam на вашу ссылку обмена. Срок - до 24 ч.";
            RewardItem item = rewardItemRepository.findByTitle(c.title()).orElseGet(RewardItem::new);
            boolean isNew = item.getId() == null;
            if (isNew) {
                item.setTitle(c.title());
                item.setCreatedAt(LocalDateTime.now());
            }
            item.setDescription(description);
            item.setCategory(Cs2CaseCatalog.CATEGORY);
            item.setPriceCoins(c.priceExc());
            item.setUserDataPrompt(TRADE_PROMPT);
            // Дешёвые кейсы без XP-гейта, дорогие - как пополнение Steam (доступ под месячный лимит трат)
            item.setMinLevelXp(c.priceExc() < 20_000 ? 0 : 5_000);
            item.setPurchaseGroup(Cs2CaseCatalog.GROUP);
            item.setActive(catalog.isEnabled());
            rewardItemRepository.save(item);
            if (isNew) log.info("[Cs2CaseSeeder] Created '{}': {} EXC, active={}", c.title(), c.priceExc(), catalog.isEnabled());
        }
        // Товары группы, которых больше нет в каталоге (старые заготовки), скрываем; не удаляем - история заявок остаётся
        java.util.Set<String> titles = catalog.all().stream().map(Cs2CaseCatalog.CaseDef::title).collect(java.util.stream.Collectors.toSet());
        for (RewardItem old : rewardItemRepository.findAll()) {
            if (Cs2CaseCatalog.GROUP.equals(old.getPurchaseGroup()) && !titles.contains(old.getTitle()) && old.isActive()) {
                old.setActive(false);
                rewardItemRepository.save(old);
                log.info("[Cs2CaseSeeder] Hidden '{}': no longer in catalog", old.getTitle());
            }
        }
    }
}
