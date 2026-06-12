package ru.gamebot.platform.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaPatchInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        apply("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS tickets BIGINT DEFAULT 0");
        apply("UPDATE app_users SET tickets = 0 WHERE tickets IS NULL");

        apply("ALTER TABLE reward_items ADD COLUMN IF NOT EXISTS photo_file_id VARCHAR(255)");
    }

    private void apply(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception exception) {
            log.warn("Schema patch skipped or failed: {}", sql, exception);
        }
    }
}
