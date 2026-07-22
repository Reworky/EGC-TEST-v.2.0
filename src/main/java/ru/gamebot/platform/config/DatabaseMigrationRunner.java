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
