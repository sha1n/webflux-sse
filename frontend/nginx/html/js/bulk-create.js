const { useState } = React;

function BulkCreateForm() {
    const [count, setCount] = useState('');
    const [isSubmitting, setIsSubmitting] = useState(false);
    const [message, setMessage] = useState('');
    const [messageType, setMessageType] = useState('');
    const [results, setResults] = useState(null);
    const [progress, setProgress] = useState('');

    const handleSubmit = async (e) => {
        e.preventDefault();

        const eventCount = parseInt(count);
        if (isNaN(eventCount) || eventCount <= 0) {
            setMessage('Please enter a positive number');
            setMessageType('error');
            return;
        }

        setIsSubmitting(true);
        setMessage('');
        setResults(null);
        setProgress(`Creating ${eventCount} events...`);

        try {
            // Counters for permission distribution and events created
            let user1Count = 0;
            let user2Count = 0;
            let user3Count = 0;
            let adminCount = 0;
            let eventsCreated = 0;

            // Create events one at a time and assign permissions immediately
            // DO NOT accumulate events in memory
            for (let i = 1; i <= eventCount; i++) {
                setProgress(`Creating event ${i} of ${eventCount}...`);

                // Step 1: Create the event
                const response = await fetch('/api/v1/events', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    body: JSON.stringify({
                        title: `event ${i}`,
                        description: null
                    }),
                });

                if (!response.ok) {
                    throw new Error(`Failed to create event ${i}`);
                }

                const event = await response.json();
                eventsCreated++;

                // Step 2: Immediately assign permissions for this event
                setProgress(`Assigning permissions for event ${i} (ID: ${event.id})...`);

                // Determine which users get access to this event randomly
                const grantToUser1 = Math.random() > 0.5;
                const grantToUser2 = Math.random() > 0.5;
                const grantToUser3 = Math.random() > 0.5;

                if (grantToUser1) user1Count++;
                if (grantToUser2) user2Count++;
                if (grantToUser3) user3Count++;
                adminCount++; // admin always gets access

                // Create permissions SEQUENTIALLY to avoid R2DBC connection pool exhaustion
                // Backend has only 10 connections; parallel requests cause blocking

                if (grantToUser1) {
                    const r1 = await fetch('/api/v1/permissions/bulk', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            userId: 'user1',
                            eventIds: [event.id]
                        }),
                    });
                    try { await r1.json(); } catch(e) {}
                }

                if (grantToUser2) {
                    const r2 = await fetch('/api/v1/permissions/bulk', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            userId: 'user2',
                            eventIds: [event.id]
                        }),
                    });
                    try { await r2.json(); } catch(e) {}
                }

                if (grantToUser3) {
                    const r3 = await fetch('/api/v1/permissions/bulk', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            userId: 'user3',
                            eventIds: [event.id]
                        }),
                    });
                    try { await r3.json(); } catch(e) {}
                }

                // Admin always gets access
                const r4 = await fetch('/api/v1/permissions/bulk', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        userId: 'admin',
                        eventIds: [event.id]
                    }),
                });
                try { await r4.json(); } catch(e) {}

                // Event and permissions are done - object can be garbage collected
            }

            // Build results
            const summary = {
                user1: user1Count,
                user2: user2Count,
                user3: user3Count,
                admin: adminCount
            };

            const totalPermissions = user1Count + user2Count + user3Count + adminCount;

            setResults({
                eventsCreated: eventsCreated,
                permissionSummary: summary,
                totalPermissions: totalPermissions
            });

            setMessage(`Successfully created ${eventsCreated} events with ${totalPermissions} permissions!`);
            setMessageType('success');
            setCount('');
            setProgress('');
        } catch (error) {
            console.error('Error creating events:', error);
            setMessage('Failed to create events. Please try again.');
            setMessageType('error');
            setProgress('');
        } finally {
            setIsSubmitting(false);
        }
    };

    return (
        <>
            <div className="api-docs-ribbon">
                <a href="/search-docs/swagger-ui.html" target="_blank">
                    API Docs
                </a>
            </div>
            <div className="container">
                <div className="header">
                    <h1>Bulk Event Creation</h1>
                </div>
                <div className="nav">
                    <a href="/">Dashboard</a>
                    <a href="/create.html">+ Create Event</a>
                    <span className="current">Bulk Create</span>
                    <a href="/search.html">Search (Stream)</a>
                    <a href="/search-sse.html">Search (SSE)</a>
                    <a href="/permissions.html">Permissions</a>
                </div>

                <div className="bulk-container">
                    <div className="bulk-form">
                        <p className="form-description">
                            Create multiple events at once with automatic permission assignment.
                            Events will be named "event 1", "event 2", etc.
                            Permissions will be randomly distributed to user1, user2, and user3 (approximately 50% each),
                            while admin will get access to all events.
                        </p>

                        {message && (
                            <div className={messageType === 'success' ? 'success-message' : 'error-message'}>
                                {message}
                            </div>
                        )}

                        {progress && (
                            <div style={{
                                background: '#d1ecf1',
                                border: '1px solid #bee5eb',
                                color: '#0c5460',
                                padding: '12px 16px',
                                borderRadius: '4px',
                                marginBottom: '20px'
                            }}>
                                {progress}
                            </div>
                        )}

                        <form onSubmit={handleSubmit}>
                            <div className="form-group">
                                <label htmlFor="count">
                                    Number of Events <span className="required">*</span>
                                </label>
                                <input
                                    id="count"
                                    type="number"
                                    value={count}
                                    onChange={(e) => setCount(e.target.value)}
                                    placeholder="Enter number of events"
                                    disabled={isSubmitting}
                                    min="1"
                                    required
                                />
                            </div>

                            <div className="form-actions">
                                <button type="submit" className="btn btn-primary" disabled={isSubmitting}>
                                    {isSubmitting ? 'Creating Events...' : 'Create Events'}
                                </button>
                            </div>
                        </form>
                    </div>

                    {results && (
                        <div className="results-container">
                            <div className="results-section">
                                <h3>Summary</h3>
                                <div style={{
                                    fontSize: '48px',
                                    fontWeight: 'bold',
                                    color: '#007bff',
                                    textAlign: 'center',
                                    padding: '20px'
                                }}>
                                    {results.eventsCreated} Events Created
                                </div>
                                <div style={{
                                    fontSize: '24px',
                                    color: '#6c757d',
                                    textAlign: 'center',
                                    marginTop: '10px'
                                }}>
                                    {results.totalPermissions} Total Permissions
                                </div>
                            </div>

                            <div className="results-section">
                                <h3>Permission Distribution</h3>
                                <div className="permission-summary">
                                    <div className="user-card">
                                        <h4>user1</h4>
                                        <div className="count">{results.permissionSummary.user1}</div>
                                        <div className="percentage">
                                            {Math.round(results.permissionSummary.user1 / results.eventsCreated * 100)}% of events
                                        </div>
                                    </div>
                                    <div className="user-card">
                                        <h4>user2</h4>
                                        <div className="count">{results.permissionSummary.user2}</div>
                                        <div className="percentage">
                                            {Math.round(results.permissionSummary.user2 / results.eventsCreated * 100)}% of events
                                        </div>
                                    </div>
                                    <div className="user-card">
                                        <h4>user3</h4>
                                        <div className="count">{results.permissionSummary.user3}</div>
                                        <div className="percentage">
                                            {Math.round(results.permissionSummary.user3 / results.eventsCreated * 100)}% of events
                                        </div>
                                    </div>
                                    <div className="user-card admin">
                                        <h4>admin</h4>
                                        <div className="count">{results.permissionSummary.admin}</div>
                                        <div className="percentage">100% of events</div>
                                    </div>
                                </div>
                            </div>
                        </div>
                    )}
                </div>
            </div>
        </>
    );
}

const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(<BulkCreateForm />);
