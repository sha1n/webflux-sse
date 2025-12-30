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
            // Step 1: Create N events sequentially
            const createdEvents = [];
            for (let i = 1; i <= eventCount; i++) {
                setProgress(`Creating event ${i} of ${eventCount}...`);

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
                createdEvents.push(event);
            }

            setProgress('Events created! Assigning permissions...');

            // Step 2: Collect event IDs
            const eventIds = createdEvents.map(e => e.id);

            // Step 3: Randomly distribute permissions
            const user1Events = [];
            const user2Events = [];
            const user3Events = [];
            const adminEvents = [...eventIds]; // admin gets all

            eventIds.forEach(eventId => {
                if (Math.random() > 0.5) user1Events.push(eventId);
                if (Math.random() > 0.5) user2Events.push(eventId);
                if (Math.random() > 0.5) user3Events.push(eventId);
            });

            // Step 4: Grant permissions via authorization server (port 8082)
            const permissionPromises = [];

            if (user1Events.length > 0) {
                setProgress('Assigning permissions to user1...');
                permissionPromises.push(
                    fetch('http://localhost:8082/api/v1/permissions/bulk', {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/json',
                        },
                        body: JSON.stringify({
                            userId: 'user1',
                            eventIds: user1Events
                        }),
                    }).then(response => {
                        if (!response.ok) {
                            console.warn('Failed to assign permissions to user1');
                        }
                        return response;
                    }).catch(error => {
                        console.error('Error assigning permissions to user1:', error);
                    })
                );
            }

            if (user2Events.length > 0) {
                setProgress('Assigning permissions to user2...');
                permissionPromises.push(
                    fetch('http://localhost:8082/api/v1/permissions/bulk', {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/json',
                        },
                        body: JSON.stringify({
                            userId: 'user2',
                            eventIds: user2Events
                        }),
                    }).then(response => {
                        if (!response.ok) {
                            console.warn('Failed to assign permissions to user2');
                        }
                        return response;
                    }).catch(error => {
                        console.error('Error assigning permissions to user2:', error);
                    })
                );
            }

            if (user3Events.length > 0) {
                setProgress('Assigning permissions to user3...');
                permissionPromises.push(
                    fetch('http://localhost:8082/api/v1/permissions/bulk', {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/json',
                        },
                        body: JSON.stringify({
                            userId: 'user3',
                            eventIds: user3Events
                        }),
                    }).then(response => {
                        if (!response.ok) {
                            console.warn('Failed to assign permissions to user3');
                        }
                        return response;
                    }).catch(error => {
                        console.error('Error assigning permissions to user3:', error);
                    })
                );
            }

            if (adminEvents.length > 0) {
                setProgress('Assigning permissions to admin...');
                permissionPromises.push(
                    fetch('http://localhost:8082/api/v1/permissions/bulk', {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/json',
                        },
                        body: JSON.stringify({
                            userId: 'admin',
                            eventIds: adminEvents
                        }),
                    }).then(response => {
                        if (!response.ok) {
                            console.warn('Failed to assign permissions to admin');
                        }
                        return response;
                    }).catch(error => {
                        console.error('Error assigning permissions to admin:', error);
                    })
                );
            }

            // Wait for all permission grants to complete (with error handling)
            await Promise.allSettled(permissionPromises);

            // Build results
            const summary = {
                user1: user1Events.length,
                user2: user2Events.length,
                user3: user3Events.length,
                admin: adminEvents.length
            };

            const totalPermissions = user1Events.length + user2Events.length +
                                    user3Events.length + adminEvents.length;

            setResults({
                events: createdEvents,
                permissionSummary: summary,
                totalPermissions: totalPermissions
            });

            setMessage(`Successfully created ${createdEvents.length} events with ${totalPermissions} permissions!`);
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
                <a href="/swagger-ui.html" target="_blank">
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
                                <h3>Created Events ({results.events.length})</h3>
                                <div className="event-list">
                                    {results.events.map(event => (
                                        <div key={event.id} className="event-badge">
                                            {event.title} (ID: {event.id})
                                        </div>
                                    ))}
                                </div>
                            </div>

                            <div className="results-section">
                                <h3>Permission Distribution</h3>
                                <div className="permission-summary">
                                    <div className="user-card">
                                        <h4>user1</h4>
                                        <div className="count">{results.permissionSummary.user1}</div>
                                        <div className="percentage">
                                            {Math.round(results.permissionSummary.user1 / results.events.length * 100)}% of events
                                        </div>
                                    </div>
                                    <div className="user-card">
                                        <h4>user2</h4>
                                        <div className="count">{results.permissionSummary.user2}</div>
                                        <div className="percentage">
                                            {Math.round(results.permissionSummary.user2 / results.events.length * 100)}% of events
                                        </div>
                                    </div>
                                    <div className="user-card">
                                        <h4>user3</h4>
                                        <div className="count">{results.permissionSummary.user3}</div>
                                        <div className="percentage">
                                            {Math.round(results.permissionSummary.user3 / results.events.length * 100)}% of events
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
