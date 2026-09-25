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
        addColumnIfMissing("app_users", "ad_reward_count", "INTEGER DEFAULT 0");
        addColumnIfMissing("app_users", "ad_reward_date", "DATE");
        addColumnIfMissing("app_users", "pending_ad_reward_at", "TIMESTAMP");
        addColumnIfMissing("app_users", "pending_ad_purpose", "VARCHAR(16)");
        addColumnIfMissing("app_users", "ad_wheel_spins", "INTEGER DEFAULT 0");
        addColumnIfMissing("app_users", "dormancy_bonus_pending_exc", "BIGINT DEFAULT 0");
        addColumnIfMissing("tournaments", "results_feed_text", "VARCHAR(4096)");
        addColumnIfMissing("app_users", "last_nudge_at", "TIMESTAMP");
        addColumnIfMissing("app_users", "last_nudge_priority", "INTEGER DEFAULT 0");
        addColumnIfMissing("app_users", "last_nudge_returned", "BOOLEAN DEFAULT FALSE");
        addColumnIfMissing("app_users", "silent_gap_nudge_sent_at", "TIMESTAMP");
        addColumnIfMissing("app_users", "egc_pass_teaser_sent_at", "TIMESTAMP");
        addColumnIfMissing("traffic_sources", "spend_rub", "BIGINT DEFAULT 0");
        addColumnIfMissing("quests", "clash_achievement_name", "VARCHAR(64)");
        addColumnIfMissing("tournaments", "season_boundary_warning_shown", "BOOLEAN DEFAULT FALSE");
        // Новое значение ScoringType (CLASH_ROYALE_TROPHIES, 21 символ) на случай нативного H2 ENUM (feedback_ddl_auto_enum_columns).
        alterColumn("tournaments", "scoring_type", "VARCHAR(32) DEFAULT 'QUEST_COUNT'");
        // Расширение enum'ов авто-проверок (2026-09-26): на случай, если колонка когда-то была создана как нативный H2 ENUM
        // с фиксированным списком значений (см. feedback_ddl_auto_enum_columns) - принудительно обычный VARCHAR; для уже
        // VARCHAR(20) - безвредное повторение.
        alterColumn("quests", "brawl_verify_type", "VARCHAR(20)");
        alterColumn("quests", "clash_verify_type", "VARCHAR(20)");
        alterColumn("quests", "clash_royale_verify_type", "VARCHAR(20)");
        createIndexIfMissing("idx_notif_user_sent", "notification_log", "user_id, sent_at");
        createIndexIfMissing("idx_notif_sent", "notification_log", "sent_at");
    }

    private void alterColumn(String table, String column, String definition) {
        try {
            jdbcTemplate.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " " + definition);
            log.info("Schema check: {}.{} -> {} OK", table, column, definition);
        } catch (Exception e) {
            log.warn("Schema alter failed for {}.{}: {}", table, column, e.getMessage());
        }
    }

    private void createIndexIfMissing(String name, String table, String columns) {
        try {
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + name + " ON " + table + "(" + columns + ")");
            log.info("Schema check: index {} OK", name);
        } catch (Exception e) {
            log.warn("Schema index {} failed: {}", name, e.getMessage());
        }
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
