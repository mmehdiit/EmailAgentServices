-- Remove duplicate email_logs, keeping the earliest row per (outlook_message_id, user_id)
DELETE FROM email_logs a
USING email_logs b
WHERE a.outlook_message_id = b.outlook_message_id
  AND a.user_id             = b.user_id
  AND a.processed_at        > b.processed_at
  AND a.outlook_message_id IS NOT NULL;

-- Drop the plain index now covered by the unique constraint
DROP INDEX IF EXISTS idx_email_logs_outlook_message;

-- Add unique constraint (also serves as an index)
ALTER TABLE email_logs
    ADD CONSTRAINT uk_email_logs_message_user UNIQUE (outlook_message_id, user_id);
