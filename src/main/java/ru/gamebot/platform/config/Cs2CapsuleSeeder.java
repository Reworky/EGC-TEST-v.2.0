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
import ru.gamebot.platform.service.Cs2CapsuleCatalog;

/** Товары-капсулы CS2 из cs2_capsules.json (по образцу Cs2SkinSeeder): создаёт и обновляет, лишние скрывает; активность = CS2_CASES_ENABLED. */
@Slf4j
@Component
@Order(6)
@RequiredArgsConstructor
public class Cs2CapsuleSeeder implements CommandLineRunner {

    private final RewardItemRepository rewardItemRepository;
    private final Cs2CapsuleCatalog catalog;

    @Override
    @Transactional
    public void run(String... args) {
        for (Cs2CapsuleCatalog.CapsuleDef c : catalog.all()) {
            String description = c.name() + ". Открывается без ключа. Придёт обменом в Steam на вашу ссылку сразу после покупки: принять обмен нужно в течение около 5 минут.";
            RewardItem item = rewardItemRepository.findByTitle(c.title()).orElseGet(RewardItem::new);
            boolean isNew = item.getId() == null;
            if (isNew) {
                item.setTitle(c.title());
                item.setCreatedAt(LocalDateTime.now());
            }
            item.setDescription(description);
            item.setCategory(Cs2CapsuleCatalog.CATEGORY);
            item.setPriceCoins(c.priceExc());
            item.setUserDataPrompt(Cs2CaseSeeder.TRADE_PROMPT);
            item.setMinLevelXp(c.priceExc() < 20_000 ? 0 : 5_000);
            item.setPurchaseGroup(Cs2CapsuleCatalog.GROUP);
            item.setActive(catalog.isEnabled());
            rewardItemRepository.save(item);
            if (isNew) log.info("[Cs2CapsuleSeeder] Created '{}': {} EXC, active={}", c.title(), c.priceExc(), catalog.isEnabled());
        }
        java.util.Set<String> titles = catalog.all().stream().map(Cs2CapsuleCatalog.CapsuleDef::title).collect(java.util.stream.Collectors.toSet());
        for (RewardItem old : rewardItemRepository.findAll()) {
            if (Cs2CapsuleCatalog.GROUP.equals(old.getPurchaseGroup()) && !titles.contains(old.getTitle()) && old.isActive()) {
                old.setActive(false);
                rewardItemRepository.save(old);
                log.info("[Cs2CapsuleSeeder] Hidden '{}': no longer in catalog", old.getTitle());
            }
        }
    }
}
