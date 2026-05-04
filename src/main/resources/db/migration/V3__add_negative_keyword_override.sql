ALTER TABLE email_logs ADD COLUMN IF NOT EXISTS negative_keyword_override VARCHAR(255);
