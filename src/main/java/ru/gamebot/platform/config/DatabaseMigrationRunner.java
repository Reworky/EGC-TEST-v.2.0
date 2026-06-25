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
        dropAllCheckConstraints("QUEST_SUBMISSIONS");
        dropAllCheckConstraints("REWARD_REQUESTS");
    }

    private void dropAllCheckConstraints(String table) {
        // H2 2.x stores constraints in INFORMATION_SCHEMA.CONSTRAINTS (not TABLE_CONSTRAINTS)
        // TABLE_NAME is uppercase in H2
        String query = "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.CONSTRAINTS " +
                       "WHERE UPPER(TABLE_NAME) = UPPER(?) AND CONSTRAINT_TYPE = 'CHECK'";
        try {
            List<String> names = jdbcTemplate.queryForList(query, String.class, table);
            log.info("Found {} CHECK constraint(s) on {}: {}", names.size(), table, names);
            for (String name : names) {
                try {
                    jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + name);
                    log.info("Dropped CHECK constraint '{}' on {}", name, table);
                } catch (Exception e) {
                    log.warn("Could not drop constraint '{}' on {}: {}", name, table, e.getMessage());
                }
            }
        } catch (Exception e) {
            // fallback: try TABLE_CONSTRAINTS view (older H2)
            log.warn("INFORMATION_SCHEMA.CONSTRAINTS failed ({}), trying TABLE_CONSTRAINTS", e.getMessage());
            try {
                List<String> names = jdbcTemplate.queryForList(
                        "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
                        "WHERE UPPER(TABLE_NAME) = UPPER(?) AND CONSTRAINT_TYPE = 'CHECK'",
                        String.class, table);
                for (String name : names) {
                    jdbcTemplate.execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS " + name);
                    log.info("Dropped CHECK constraint '{}' on {}", name, table);
                }
            } catch (Exception e2) {
                log.warn("Both attempts failed for {}: {}", table, e2.getMessage());
            }
        }
    }
}
