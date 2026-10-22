-- Existing demo users keep their current experience and preferences.
ALTER TABLE app_users ADD COLUMN onboarding_completed BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE app_users ALTER COLUMN onboarding_completed SET DEFAULT FALSE;
