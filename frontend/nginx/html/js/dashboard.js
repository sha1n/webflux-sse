const { useState, useEffect } = React;

function EventDashboard() {
    const [events, setEvents] = useState([]);
    const [connectionStatus, setConnectionStatus] = useState('connecting');

    useEffect(() => {
        const eventSource = new EventSource('/api/v1/events');
        let currentBatch = [];
        let batchTimeout = null;
        let connectionTimeout = null;

        // Set a timeout to detect if we don't receive any data within 5 seconds
        connectionTimeout = setTimeout(() => {
            if (eventSource.readyState !== EventSource.OPEN) {
                console.log('❌ Connection timeout - no response from server');
                setConnectionStatus('disconnected');
            }
        }, 5000);

        eventSource.onopen = () => {
            console.log('✅ SSE connection opened');
            // Don't set to connected yet, wait for actual data or heartbeat
        };

        // Handle heartbeat events (sent when there are no data events)
        eventSource.addEventListener('heartbeat', () => {
            console.log('💓 Received heartbeat');
            if (connectionTimeout) {
                clearTimeout(connectionTimeout);
                connectionTimeout = null;
            }
            setConnectionStatus('connected');
        });

        eventSource.onmessage = (event) => {
            if (connectionTimeout) {
                clearTimeout(connectionTimeout);
                connectionTimeout = null;
            }
            setConnectionStatus('connected');

            try {
                const eventData = JSON.parse(event.data);
                console.log('📨 Received event:', eventData);

                currentBatch.push(eventData);

                if (batchTimeout) {
                    clearTimeout(batchTimeout);
                }

                batchTimeout = setTimeout(() => {
                    setEvents(currentBatch.slice(0, 100));
                    currentBatch = [];
                }, 100);

            } catch (error) {
                console.error('❌ Error parsing event data:', error);
            }
        };

        eventSource.onerror = (error) => {
            console.error('⚠️ SSE error:', error, 'readyState:', eventSource.readyState);

            if (connectionTimeout) {
                clearTimeout(connectionTimeout);
                connectionTimeout = null;
            }

            // Show disconnected if connection is closed or connecting failed
            if (eventSource.readyState === EventSource.CLOSED) {
                console.log('❌ Connection closed - setting status to disconnected');
                setConnectionStatus('disconnected');
            } else if (eventSource.readyState === EventSource.CONNECTING) {
                console.log('⏳ Connection failed, retrying...');
                setConnectionStatus('connecting');
            }
        };

        return () => {
            if (connectionTimeout) {
                clearTimeout(connectionTimeout);
            }
            if (batchTimeout) {
                clearTimeout(batchTimeout);
            }
            eventSource.close();
        };
    }, []);

    const formatTimestamp = (timestamp) => {
        return new Date(timestamp).toLocaleString();
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
                    <h1>Event Stream Dashboard</h1>
                </div>
                <div className="status">
                    Connection Status:
                    <span className={connectionStatus}>
                        {connectionStatus === 'connected' && ' ● Connected'}
                        {connectionStatus === 'connecting' && ' ● Connecting...'}
                        {connectionStatus === 'disconnected' && ' ● Disconnected'}
                    </span>
                    {events.length > 0 && <span> | Events: {events.length}</span>}
                </div>
                <div className="nav">
                    <span className="current">Dashboard</span>
                    <a href="/create.html" className="primary">+ Create Event</a>
                    <a href="/bulk-create.html">Bulk Create</a>
                    <a href="/search.html">Search (Stream)</a>
                    <a href="/search-sse.html">Search (SSE)</a>
                    <a href="/permissions.html">Permissions</a>
                </div>
            <div className="table-container">
                {connectionStatus === 'disconnected' ? (
                    <div className="empty-state">
                        <p>⚠️ Server is not available. Please make sure the services are running.</p>
                    </div>
                ) : connectionStatus === 'connecting' ? (
                    <table>
                        <thead>
                            <tr>
                                <th>ID</th>
                                <th>Timestamp</th>
                                <th>Title</th>
                                <th>Description</th>
                            </tr>
                        </thead>
                        <tbody>
                            <tr>
                                <td colSpan="4" className="empty-message">
                                    Connecting to server...
                                </td>
                            </tr>
                        </tbody>
                    </table>
                ) : (
                    <table>
                        <thead>
                            <tr>
                                <th>ID</th>
                                <th>Timestamp</th>
                                <th>Title</th>
                                <th>Description</th>
                            </tr>
                        </thead>
                        <tbody>
                            {events.length === 0 ? (
                                <tr>
                                    <td colSpan="4" className="empty-message">
                                        No events yet. <a href="/create.html">Create your first event</a>
                                    </td>
                                </tr>
                            ) : (
                                events.map((event) => (
                                    <tr key={event.id}>
                                        <td className="event-id">{event.id}</td>
                                        <td className="timestamp">{formatTimestamp(event.timestamp)}</td>
                                        <td className="title">{event.title}</td>
                                        <td className="description">{event.description}</td>
                                    </tr>
                                ))
                            )}
                        </tbody>
                    </table>
                )}
            </div>
        </div>
        </>
    );
}

const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(<EventDashboard />);