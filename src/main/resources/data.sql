INSERT INTO events (timestamp, title, description) VALUES 
    (CURRENT_TIMESTAMP - INTERVAL '1 hour', 'System Startup', 'Application started successfully'),
    (CURRENT_TIMESTAMP - INTERVAL '45 minutes', 'User Registration', 'New user registered with ID 12345'),
    (CURRENT_TIMESTAMP - INTERVAL '30 minutes', 'Database Backup', 'Scheduled database backup completed'),
    (CURRENT_TIMESTAMP - INTERVAL '20 minutes', 'API Error', 'Authentication service returned 503 error'),
    (CURRENT_TIMESTAMP - INTERVAL '10 minutes', 'Performance Alert', 'CPU usage exceeded 85% threshold'),
    (CURRENT_TIMESTAMP - INTERVAL '5 minutes', 'Cache Clear', 'Application cache cleared successfully'),
    (CURRENT_TIMESTAMP, 'Health Check', 'All systems operational')
ON CONFLICT DO NOTHING;