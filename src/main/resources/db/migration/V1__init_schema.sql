-- V1: Initial schema for Email Agent Services

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS user_roles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(50) NOT NULL DEFAULT 'user',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, role)
);

CREATE TABLE IF NOT EXISTS outlook_connections (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    email_address VARCHAR(255),
    access_token TEXT,
    refresh_token TEXT,
    token_expiry TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS email_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    subject_template TEXT,
    body_template TEXT,
    font_family VARCHAR(100) DEFAULT 'Arial',
    primary_color VARCHAR(20) DEFAULT '#0C799A',
    text_color VARCHAR(20) DEFAULT '#333333',
    background_color VARCHAR(20) DEFAULT '#ffffff',
    header_image_url TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS forwarding_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    keywords TEXT[] DEFAULT '{}',
    negative_keywords TEXT[] DEFAULT '{}',
    recipient_email VARCHAR(255) NOT NULL,
    conditions TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    priority INTEGER NOT NULL DEFAULT 0,
    sender_pattern VARCHAR(255),
    subject_pattern TEXT,
    ai_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ai_context TEXT,
    rotation_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    current_rotation_index INTEGER NOT NULL DEFAULT 0,
    smart_thread_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    extract_attachments BOOLEAN NOT NULL DEFAULT FALSE,
    template_id UUID REFERENCES email_templates(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS rule_recipients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id UUID NOT NULL REFERENCES forwarding_rules(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    display_name VARCHAR(255),
    sort_order INTEGER NOT NULL DEFAULT 0,
    is_on_vacation BOOLEAN NOT NULL DEFAULT FALSE,
    vacation_start TIMESTAMPTZ,
    vacation_end TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS email_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email_from VARCHAR(255),
    email_subject TEXT,
    forwarded_to VARCHAR(255),
    rule_matched UUID REFERENCES forwarding_rules(id) ON DELETE SET NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'no_match',
    outlook_message_id TEXT,
    outlook_conversation_id TEXT,
    processed_at TIMESTAMPTZ DEFAULT NOW(),
    replied_at TIMESTAMPTZ,
    reply_detected BOOLEAN NOT NULL DEFAULT FALSE,
    ai_classified BOOLEAN NOT NULL DEFAULT FALSE,
    ai_confidence DOUBLE PRECISION,
    ai_reasoning TEXT,
    tracking_token UUID,
    reply_source VARCHAR(50),
    received_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_forwarding_rules_user_id ON forwarding_rules(user_id);
CREATE INDEX IF NOT EXISTS idx_forwarding_rules_active ON forwarding_rules(user_id, active);
CREATE INDEX IF NOT EXISTS idx_rule_recipients_rule_id ON rule_recipients(rule_id);
CREATE INDEX IF NOT EXISTS idx_email_logs_user_id ON email_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_email_logs_tracking_token ON email_logs(tracking_token);
CREATE INDEX IF NOT EXISTS idx_email_logs_outlook_message ON email_logs(outlook_message_id, user_id);
CREATE INDEX IF NOT EXISTS idx_email_logs_processed_at ON email_logs(user_id, processed_at DESC);
CREATE INDEX IF NOT EXISTS idx_outlook_connections_user_id ON outlook_connections(user_id);
