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
        deduplicateQuests();
        backfillQuestTicketRewards();
        seedEgcAvatarFrame();
        fixSponsoredQuestFlag();
        seedGtaVQuests();
        seedGtaVCatalog();
        deleteGamesAndQuests();
        fixNullDurationText();
    }

    private void deduplicateQuests() {
        try {
            List<Map<String, Object>> dups = jdbcTemplate.queryForList(
                "SELECT title, game_name, COUNT(*) as cnt FROM quests GROUP BY title, game_name HAVING COUNT(*) > 1");
            for (Map<String, Object> row : dups) {
                String title = (String) row.get("TITLE");
                String gameName = (String) row.get("GAME_NAME");
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id FROM quests WHERE title = ? AND game_name = ? ORDER BY id ASC", title, gameName);
                // Keep the first (oldest), delete the rest
                for (int i = 1; i < rows.size(); i++) {
                    Long id = ((Number) rows.get(i).get("ID")).longValue();
                    jdbcTemplate.update("DELETE FROM quest_submissions WHERE quest_id = ?", id);
                    jdbcTemplate.update("DELETE FROM quests WHERE id = ?", id);
                    log.warn("[DBMigration] Removed duplicate quest id={} title='{}' game='{}'", id, title, gameName);
                }
            }
        } catch (Exception e) {
            log.error("[DBMigration] deduplicateQuests failed: {}", e.getMessage());
        }
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

    private void fixNullDurationText() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, duration_days FROM quests WHERE duration_text IS NULL AND duration_days > 0");
            for (Map<String, Object> row : rows) {
                int days = ((Number) row.get("DURATION_DAYS")).intValue();
                String text = days + " " + (days == 1 ? "день" : days < 5 ? "дня" : "дней");
                jdbcTemplate.update("UPDATE quests SET duration_text = ? WHERE id = ?", text, row.get("ID"));
            }
            if (!rows.isEmpty()) log.info("[DBMigration] Fixed duration_text for {} quests", rows.size());
        } catch (Exception e) {
            log.error("[DBMigration] fixNullDurationText failed: {}", e.getMessage());
        }
    }

    private void deleteGamesAndQuests() {
        try {
            for (String game : List.of("EA FC 26", "Mobile Legends: Bang Bang")) {
                // Delete submissions for quests of this game
                jdbcTemplate.update(
                    "DELETE FROM quest_submissions WHERE quest_id IN (SELECT id FROM quests WHERE game_name = ?)", game);
                // Delete quests
                int deleted = jdbcTemplate.update("DELETE FROM quests WHERE game_name = ?", game);
                // Delete from catalog
                jdbcTemplate.update("DELETE FROM game_catalog WHERE game_name = ?", game);
                if (deleted > 0) log.info("[DBMigration] Deleted {} quests and catalog entry for '{}'", deleted, game);
            }
        } catch (Exception e) {
            log.error("[DBMigration] deleteGamesAndQuests failed: {}", e.getMessage());
        }
    }

    private void seedGtaVCatalog() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM game_catalog WHERE game_name = 'GTA V'", Integer.class);
            if (count != null && count > 0) return;
            jdbcTemplate.update("INSERT INTO game_catalog (game_name) VALUES ('GTA V')");
            log.info("[DBMigration] Added GTA V to game catalog");
        } catch (Exception e) {
            log.error("[DBMigration] seedGtaVCatalog failed: {}", e.getMessage());
        }
    }

    private void seedGtaVQuests() {
        try {
            seedQuestIfAbsent(
                "Добро пожаловать в Лос-Сантос", "GTA V",
                "Зайди в GTA Online и сыграй одну любую миссию до конца.\nПодойдёт любая — контракт, работа, миссия от персонажа.",
                "Лёгкие", "PC, PS4/5, Xbox", 3, 50, 1500, 1,
                "Скриншот экрана завершения миссии с надписью \"Mission Passed\" и наградой. Ник персонажа должен быть виден."
            );
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
            String durationText = durationDays + " " + (durationDays == 1 ? "день" : durationDays < 5 ? "дня" : "дней");
            jdbcTemplate.update(
                "INSERT INTO quests (title, game_name, description, category, platform, duration_days, duration_text, reward_xp, reward_coins, ticket_reward, requirements, active, council_only, season_only, sponsored, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, false, false, false, NOW())",
                title, gameName, description, category, platform, durationDays, durationText, rewardXp, rewardCoins, ticketReward, requirements);
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
