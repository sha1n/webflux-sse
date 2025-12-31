const { useState, useEffect, useRef } = React;

function EventDashboard() {
    const [events, setEvents] = useState([]);
    const [connectionStatus, setConnectionStatus] = useState('connecting');
    const [lastUpdateTime, setLastUpdateTime] = useState(null);
    const latestTimestampRef = useRef(null);
    const MAX_EVENTS = 100; // Maximum number of events to keep in memory

    useEffect(() => {
        let eventSource = null;
        let currentBatch = [];
        let batchTimeout = null;
        let heartbeatTimeout = null;
        let reconnectTimeout = null;
        let isCleaningUp = false;

        const connect = () => {
            if (isCleaningUp) return;

            // Use 'since' parameter to only fetch events after the latest one we have
            const url = latestTimestampRef.current
                ? `/api/v1/events?since=${encodeURIComponent(latestTimestampRef.current)}`
                : '/api/v1/events';

            console.log(`🔄 Connecting to SSE stream: ${url}`);
            eventSource = new EventSource(url);

            const resetHeartbeatTimeout = () => {
                if (heartbeatTimeout) {
                    clearTimeout(heartbeatTimeout);
                }
                // If we don't receive a heartbeat within 10 seconds, reconnect
                heartbeatTimeout = setTimeout(() => {
                    if (isCleaningUp) return;
                    console.log('💔 No heartbeat received in 10s - reconnecting...');
                    setConnectionStatus('connecting');
                    eventSource.close();
                    if (reconnectTimeout) clearTimeout(reconnectTimeout);
                    reconnectTimeout = setTimeout(connect, 1000);
                }, 10000);
            };

            eventSource.onopen = () => {
                console.log('✅ SSE connection opened');
                setConnectionStatus('connected');
                resetHeartbeatTimeout();
            };

            // Handle heartbeat events to keep connection alive
            eventSource.addEventListener('heartbeat', () => {
                console.log('💓 Received heartbeat');
                setConnectionStatus('connected');
                resetHeartbeatTimeout();
            });

            eventSource.onmessage = (event) => {
                setConnectionStatus('connected');
                resetHeartbeatTimeout();

                try {
                    const eventData = JSON.parse(event.data);
                    console.log('📨 Received event:', eventData);

                    currentBatch.push(eventData);

                    if (batchTimeout) {
                        clearTimeout(batchTimeout);
                    }

                    batchTimeout = setTimeout(() => {
                        setEvents(prevEvents => {
                            // Merge new events with existing ones
                            const mergedEvents = [...currentBatch, ...prevEvents];

                            // Remove duplicates by ID
                            const uniqueEvents = Array.from(
                                new Map(mergedEvents.map(e => [e.id, e])).values()
                            );

                            // Sort by timestamp (newest first)
                            uniqueEvents.sort((a, b) =>
                                new Date(b.timestamp) - new Date(a.timestamp)
                            );

                            // Keep only the most recent MAX_EVENTS
                            const limitedEvents = uniqueEvents.slice(0, MAX_EVENTS);

                            // Update the latest timestamp for next reconnection
                            if (limitedEvents.length > 0) {
                                latestTimestampRef.current = limitedEvents[0].timestamp;
                            }

                            return limitedEvents;
                        });
                        setLastUpdateTime(new Date());
                        currentBatch = [];
                    }, 100);

                } catch (error) {
                    console.error('❌ Error parsing event data:', error);
                }
            };

            eventSource.onerror = (error) => {
                if (isCleaningUp) return;

                console.error('⚠️ SSE error:', error, 'readyState:', eventSource.readyState);

                if (heartbeatTimeout) {
                    clearTimeout(heartbeatTimeout);
                    heartbeatTimeout = null;
                }

                // Show connecting status and attempt to reconnect
                if (eventSource.readyState === EventSource.CLOSED) {
                    console.log('❌ Connection closed - reconnecting in 2s...');
                    setConnectionStatus('connecting');
                    eventSource.close();
                    if (reconnectTimeout) clearTimeout(reconnectTimeout);
                    reconnectTimeout = setTimeout(connect, 2000);
                } else if (eventSource.readyState === EventSource.CONNECTING) {
                    console.log('⏳ Connection failed, retrying...');
                    setConnectionStatus('connecting');
                }
            };
        };

        // Initial connection
        connect();

        return () => {
            console.log('🧹 Cleaning up SSE connection');
            isCleaningUp = true;
            if (heartbeatTimeout) clearTimeout(heartbeatTimeout);
            if (batchTimeout) clearTimeout(batchTimeout);
            if (reconnectTimeout) clearTimeout(reconnectTimeout);
            if (eventSource) eventSource.close();
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
                    {events.length > 0 && (
                        <span>
                            {' | '}
                            Showing {events.length} most recent event{events.length !== 1 ? 's' : ''}
                            {lastUpdateTime && (
                                <span style={{ marginLeft: '8px', color: '#666' }}>
                                    • Updated: {lastUpdateTime.toLocaleTimeString()}
                                </span>
                            )}
                        </span>
                    )}
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