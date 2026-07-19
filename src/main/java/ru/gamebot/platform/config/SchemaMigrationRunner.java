package ru.gamebot.platform.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs safe ADD COLUMN IF NOT EXISTS migrations before the app starts.
 * Needed because ddl-auto=update can fail to add columns when a previous
 * startup aborted mid-DDL.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SchemaMigrationRunner implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        addColumnIfMissing("app_users", "welcome_bonus_paid", "BOOLEAN DEFAULT FALSE");
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        try {
            jdbcTemplate.execute(
                "ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + column + " " + definition
            );
            log.info("Schema check: {}.{} OK", table, column);
        } catch (Exception e) {
            log.warn("Schema migration failed for {}.{}: {}", table, column, e.getMessage());
        }
    }
}
