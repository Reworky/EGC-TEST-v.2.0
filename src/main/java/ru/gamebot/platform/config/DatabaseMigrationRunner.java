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
        logAllConstraints();
        dropStatusCheckConstraint("QUEST_SUBMISSIONS", "STATUS");
        dropStatusCheckConstraint("REWARD_REQUESTS", "STATUS");
    }

    private void logAllConstraints() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE, TABLE_NAME, TABLE_SCHEMA " +
                    "FROM INFORMATION_SCHEMA.CONSTRAINTS");
            log.error("[DBMigration] All constraints in DB ({} total): {}", rows.size(), rows);
        } catch (Exception e) {
            log.error("[DBMigration] Could not query INFORMATION_SCHEMA.CONSTRAINTS: {}", e.getMessage());
        }
    }

    private void dropStatusCheckConstraint(String table, String column) {
        // Strategy 1: find by table name in INFORMATION_SCHEMA.CONSTRAINTS
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CONSTRAINTS " +
                    "WHERE UPPER(TABLE_NAME) = UPPER(?) AND CONSTRAINT_TYPE = 'CHECK'",
                    String.class, table);
            log.error("[DBMigration] Found {} CHECK constraints on {}: {}", names.size(), table, names);
            for (String name : names) {
                try {
                    jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + name);
                    log.error("[DBMigration] Dropped: {}", name);
                } catch (Exception ex) {
                    log.error("[DBMigration] Drop failed for {}: {}", name, ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[DBMigration] Strategy 1 failed for {}: {}", table, e.getMessage());
        }

        // Strategy 2: ALTER COLUMN to remove check — H2 specific
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " VARCHAR(255)");
            log.error("[DBMigration] ALTER COLUMN done on {}.{}", table, column);
        } catch (Exception e) {
            log.error("[DBMigration] ALTER COLUMN failed on {}.{}: {}", table, column, e.getMessage());
        }
    }
}
