package ru.gamebot.platform.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseMigrationRunner implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        // Log ALL constraints so we can see what's actually in the DB
        try {
            List<Map<String, Object>> all = jdbcTemplate.queryForList(
                    "SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE, TABLE_NAME " +
                    "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS");
            log.error("[DBMigration] All TABLE_CONSTRAINTS ({} total): {}", all.size(), all);
        } catch (Exception e) {
            log.error("[DBMigration] Cannot query TABLE_CONSTRAINTS: {}", e.getMessage());
        }

        dropCheckConstraints("QUEST_SUBMISSIONS");
        dropCheckConstraints("REWARD_REQUESTS");
        backfillQuestTicketRewards();
        seedEgcAvatarFrame();
        fixSponsoredQuestFlag();
        seedGtaVQuests();
    }

    private void backfillQuestTicketRewards() {
        try {
            int updated = jdbcTemplate.update(
                "UPDATE quests SET ticket_reward = CASE category " +
                "WHEN 'Лёгкие' THEN 1 WHEN 'Средние' THEN 2 WHEN 'Сложные' THEN 3 ELSE 1 END " +
                "WHERE ticket_reward = 0");
            if (updated > 0) {
                log.info("[DBMigration] backfilled ticket_reward for {} quests", updated);
            }
        } catch (Exception e) {
            log.error("[DBMigration] backfillQuestTicketRewards failed: {}", e.getMessage());
        }
    }

    private void seedGtaVQuests() {
        try {
            seedQuestIfAbsent(
                "Первый миллион", "GTA V",
                "Заработай 1,000,000 $ в GTA Online.\nДеньги можно получить любым способом — миссии, гонки, бизнес, продажа товара.",
                "Лёгкие", "PC, PS4/5, Xbox", 7, 50, 1500, 1,
                "Скриншот экрана персонажа с видимым балансом $1,000,000+ в правом углу экрана. На скриншоте должен быть виден ник персонажа."
            );
            seedQuestIfAbsent(
                "Звёзды розыска", "GTA V",
                "Получи 5 звёзд розыска в GTA Online и продержись с ними живым 3 минуты.\nУбегай, прячься или отстреливайся — главное выжить.",
                "Средние", "PC, PS4/5, Xbox", 7, 100, 4000, 2,
                "Скриншот экрана с 5 звёздами розыска в правом верхнем углу и таймером или временем сессии. Ник персонажа должен быть виден."
            );
            seedQuestIfAbsent(
                "Король Лос-Сантоса", "GTA V",
                "Купи любую недвижимость в GTA Online — гараж, квартиру, бизнес или клубный дом.\nПокупка должна быть совершена в рамках текущей игровой сессии, не ранее.",
                "Сложные", "PC, PS4/5, Xbox", 14, 250, 10000, 3,
                "Скриншот экрана подтверждения покупки с названием недвижимости и суммой сделки. Ник персонажа должен быть виден."
            );
        } catch (Exception e) {
            log.error("[DBMigration] seedGtaVQuests failed: {}", e.getMessage());
        }
    }

    private void seedQuestIfAbsent(String title, String gameName, String description,
            String category, String platform, int durationDays, long rewardXp, long rewardCoins,
            int ticketReward, String requirements) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM quests WHERE title = ? AND game_name = ?", Integer.class, title, gameName);
            if (count != null && count > 0) return;
            jdbcTemplate.update(
                "INSERT INTO quests (title, game_name, description, category, platform, duration_days, reward_xp, reward_coins, ticket_reward, requirements, active, council_only, season_only, sponsored, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, false, false, false, NOW())",
                title, gameName, description, category, platform, durationDays, rewardXp, rewardCoins, ticketReward, requirements);
            log.info("[DBMigration] Inserted quest '{}' for {}", title, gameName);
        } catch (Exception e) {
            log.error("[DBMigration] seedQuestIfAbsent '{}' failed: {}", title, e.getMessage());
        }
    }

    private void fixSponsoredQuestFlag() {
        try {
            int updated = jdbcTemplate.update(
                "UPDATE quests SET sponsored = true WHERE sponsor_id IS NOT NULL AND sponsored = false");
            if (updated > 0) {
                log.info("[DBMigration] Fixed sponsored flag for {} quests", updated);
            }
        } catch (Exception e) {
            log.error("[DBMigration] fixSponsoredQuestFlag failed: {}", e.getMessage());
        }
    }

    private void seedEgcAvatarFrame() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reward_items WHERE avatar_frame_image = 'egc'", Integer.class);
            if (count != null && count > 0) return;
            jdbcTemplate.update(
                "INSERT INTO reward_items (title, description, category, price_coins, active, purchase_group, avatar_frame_image, avatar_frame_color, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())",
                "👑 Рамка EGC", "Эксклюзивная рамка аватара Experience Gaming Club", "Рамка",
                50000, true, "avatar_frame", "egc", "#7C3AED");
            log.info("[DBMigration] Inserted EGC avatar frame");
        } catch (Exception e) {
            log.error("[DBMigration] seedEgcAvatarFrame failed: {}", e.getMessage());
        }
    }

    private void dropCheckConstraints(String table) {
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
                    "WHERE UPPER(TABLE_NAME) = ? AND CONSTRAINT_TYPE = 'CHECK'",
                    String.class, table.toUpperCase());
            log.error("[DBMigration] CHECK constraints on {}: {}", table, names);
            for (String name : names) {
                jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT \"" + name + "\"");
                log.error("[DBMigration] Dropped constraint '{}' on {}", name, table);
            }
        } catch (Exception e) {
            log.error("[DBMigration] Failed for {}: {}", table, e.getMessage());
        }
    }
}
