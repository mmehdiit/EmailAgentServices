-- V7: Make password_hash nullable to support Microsoft Entra ID-only authentication
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
