package ru.gamebot.platform.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseMigrationRunner implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        dropCheckConstraints("QUEST_SUBMISSIONS");
        dropCheckConstraints("REWARD_REQUESTS");
    }

    private void dropCheckConstraints(String table) {
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
                    "WHERE TABLE_NAME = ? AND CONSTRAINT_TYPE = 'CHECK'",
                    String.class, table);
            for (String name : names) {
                jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS \"" + name + "\"");
                log.info("Dropped CHECK constraint {} on {}", name, table);
            }
        } catch (Exception e) {
            log.warn("Could not drop CHECK constraints on {}: {}", table, e.getMessage());
        }
    }
}
