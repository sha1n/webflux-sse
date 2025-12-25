-- Database initialization script for WebFlux SSE Demo
-- Creates tables for both search-server and authorization-server

-- Create the events table (used by search-server)
CREATE TABLE IF NOT EXISTS events (
    id BIGSERIAL PRIMARY KEY,
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    title VARCHAR(255) NOT NULL,
    description TEXT
);

-- Create the user event permissions table (used by authorization-server)
-- Note: References events table with cascading delete
CREATE TABLE IF NOT EXISTS user_event_permissions (
    id BIGSERIAL PRIMARY KEY,
    event_id BIGINT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    user_id VARCHAR(255) NOT NULL,
    UNIQUE(event_id, user_id)
);
