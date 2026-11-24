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
import ru.gamebot.platform.service.Cs2SkinCatalog;

/**
 * Товары-скины CS2 из cs2_skins.json (по образцу Cs2CaseSeeder). Только создаёт и обновляет; товары группы, которых нет в каталоге, скрывает
 * (не удаляет - история заявок остаётся). Активность = CS2_CASES_ENABLED.
 */
@Slf4j
@Component
@Order(5)
@RequiredArgsConstructor
public class Cs2SkinSeeder implements CommandLineRunner {

    private final RewardItemRepository rewardItemRepository;
    private final Cs2SkinCatalog catalog;

    @Override
    @Transactional
    public void run(String... args) {
        for (Cs2SkinCatalog.SkinDef s : catalog.all()) {
            String description = s.displayName() + ". Придёт обменом в Steam на вашу ссылку сразу после покупки: принять обмен нужно в течение около 5 минут.";
            RewardItem item = rewardItemRepository.findByTitle(s.title()).orElseGet(RewardItem::new);
            boolean isNew = item.getId() == null;
            if (isNew) {
                item.setTitle(s.title());
                item.setCreatedAt(LocalDateTime.now());
            }
            item.setDescription(description);
            item.setCategory(Cs2SkinCatalog.CATEGORY);
            item.setPriceCoins(s.priceExc());
            item.setUserDataPrompt(Cs2CaseSeeder.TRADE_PROMPT);
            item.setMinLevelXp(s.priceExc() < 20_000 ? 0 : 5_000);
            item.setPurchaseGroup(Cs2SkinCatalog.GROUP);
            item.setActive(catalog.isEnabled());
            rewardItemRepository.save(item);
            if (isNew) log.info("[Cs2SkinSeeder] Created '{}': {} EXC, active={}", s.title(), s.priceExc(), catalog.isEnabled());
        }
        java.util.Set<String> titles = catalog.all().stream().map(Cs2SkinCatalog.SkinDef::title).collect(java.util.stream.Collectors.toSet());
        for (RewardItem old : rewardItemRepository.findAll()) {
            if (Cs2SkinCatalog.GROUP.equals(old.getPurchaseGroup()) && !titles.contains(old.getTitle()) && old.isActive()) {
                old.setActive(false);
                rewardItemRepository.save(old);
                log.info("[Cs2SkinSeeder] Hidden '{}': no longer in catalog", old.getTitle());
            }
        }
    }
}
