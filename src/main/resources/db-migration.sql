ALTER TABLE quest ADD COLUMN IF NOT EXISTS ticket_reward INT DEFAULT 0 NOT NULL;

-- AI verification columns for quest_submissions
ALTER TABLE quest_submissions ADD COLUMN IF NOT EXISTS ai_decision VARCHAR(10);
ALTER TABLE quest_submissions ADD COLUMN IF NOT EXISTS ai_confidence DOUBLE;
ALTER TABLE quest_submissions ADD COLUMN IF NOT EXISTS ai_reason VARCHAR(2000);
ALTER TABLE quest_submissions ADD COLUMN IF NOT EXISTS ai_checks VARCHAR(4000);
ALTER TABLE quest_submissions ADD COLUMN IF NOT EXISTS ai_reviewed_at TIMESTAMP;
ALTER TABLE quest_submissions ADD COLUMN IF NOT EXISTS reviewed_by VARCHAR(100);
