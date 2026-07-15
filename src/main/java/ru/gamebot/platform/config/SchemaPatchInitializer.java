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
        apply("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS profile_completed BOOLEAN DEFAULT FALSE");
        apply("UPDATE app_users SET profile_completed = registration_completed WHERE profile_completed IS NULL OR profile_completed = FALSE");
        apply("UPDATE quests SET category = 'Лёгкие' WHERE category IN ('Быстрые', 'Легкие', 'легкие', 'Лёгкие задания', 'Легкие задания')");
        apply("UPDATE quests SET category = 'Средние' WHERE category IN ('Средние задания', 'средние')");
        apply("UPDATE quests SET category = 'Сложные' WHERE category IN ('Долгие', 'Сложные задания', 'сложные')");

        apply("ALTER TABLE reward_items ADD COLUMN IF NOT EXISTS photo_file_id VARCHAR(255)");

        // Boost & sink shop columns
        apply("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS xp_boost_active_until TIMESTAMP");
        apply("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS quest_slot_extra_until TIMESTAMP");
        apply("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS cooldown_bypass_game VARCHAR(255)");
        apply("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS daily_boost_count INT DEFAULT 0");
        apply("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS daily_boost_date DATE");
        apply("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS daily_cooldown_removals INT DEFAULT 0");
        apply("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS daily_cooldown_date DATE");
        apply("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS daily_gifts_sent INT DEFAULT 0");
        apply("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS daily_gift_sent_date DATE");
        apply("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS daily_gifts_received INT DEFAULT 0");
        apply("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS daily_gift_received_date DATE");
        apply("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS daily_reroll_count INT DEFAULT 0");
        apply("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS daily_reroll_date DATE");

        // Withdrawal limit columns
        apply("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS monthly_withdrawn_exc BIGINT DEFAULT 0");
        apply("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS withdrawal_month INT DEFAULT 0");
        apply("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS withdrawal_year INT DEFAULT 0");

        // USDT withdrawal support
        apply("ALTER TABLE reward_requests ADD COLUMN IF NOT EXISTS payout_details VARCHAR(512)");

        // Game currency shop — user data collection
        apply("ALTER TABLE reward_items ADD COLUMN IF NOT EXISTS user_data_prompt VARCHAR(512)");

        // Fixed rub economy
        apply("ALTER TABLE quest_submissions ADD COLUMN IF NOT EXISTS fixed_rub_value BIGINT DEFAULT NULL");
        apply("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS fixed_rub_balance BIGINT DEFAULT 0");
        apply("UPDATE app_users SET fixed_rub_balance = 0 WHERE fixed_rub_balance IS NULL");
    }

    private void apply(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception exception) {
            log.warn("Schema patch skipped or failed: {}", sql, exception);
        }
    }
}
