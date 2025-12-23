-- Create the user event permissions table
-- Note: Assumes events table already exists
CREATE TABLE IF NOT EXISTS user_event_permissions (
    id BIGSERIAL PRIMARY KEY,
    event_id BIGINT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    user_id VARCHAR(255) NOT NULL,
    UNIQUE(event_id, user_id)
);
