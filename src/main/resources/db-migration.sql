ALTER TABLE quest ADD COLUMN IF NOT EXISTS ticket_reward INT DEFAULT 0 NOT NULL;

-- Onboarding flow columns for app_users
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS onboarding_step INT DEFAULT 0;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS onboarding_completed BOOLEAN DEFAULT FALSE;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS onboarding_game VARCHAR(100);
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS onboarding_quest_id BIGINT;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS onboarding_started_at TIMESTAMP;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS onboarding_completed_at TIMESTAMP;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS onboarding_notifications_sent INT DEFAULT 0;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS last_onboarding_notification TIMESTAMP;
-- Existing registered users skip onboarding
UPDATE app_users SET onboarding_completed = TRUE WHERE registration_completed = TRUE;

-- AI verification columns for quest_submissions
ALTER TABLE quest_submissions ADD COLUMN IF NOT EXISTS ai_decision VARCHAR(10);
ALTER TABLE quest_submissions ADD COLUMN IF NOT EXISTS ai_confidence DOUBLE;
ALTER TABLE quest_submissions ADD COLUMN IF NOT EXISTS ai_reason VARCHAR(2000);
ALTER TABLE quest_submissions ADD COLUMN IF NOT EXISTS ai_checks VARCHAR(4000);
ALTER TABLE quest_submissions ADD COLUMN IF NOT EXISTS ai_reviewed_at TIMESTAMP;
ALTER TABLE quest_submissions ADD COLUMN IF NOT EXISTS reviewed_by VARCHAR(100);
