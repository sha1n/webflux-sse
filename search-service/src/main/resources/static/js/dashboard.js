const { useState, useEffect } = React;

function EventDashboard() {
    const [events, setEvents] = useState([]);
    const [connectionStatus, setConnectionStatus] = useState('disconnected');

    useEffect(() => {
        const eventSource = new EventSource('/api/events/stream');
        let currentBatch = [];
        let batchTimeout = null;

        eventSource.onopen = () => {
            setConnectionStatus('connected');
        };

        eventSource.onmessage = (event) => {
            try {
                const eventData = JSON.parse(event.data);
                
                // Add event to current batch
                currentBatch.push(eventData);
                
                // Clear any existing timeout
                if (batchTimeout) {
                    clearTimeout(batchTimeout);
                }
                
                // Set a timeout to process the batch after events stop coming
                batchTimeout = setTimeout(() => {
                    // Events are already in DESC order from DB, so keep them in that order
                    setEvents(currentBatch.slice(0, 100));
                    currentBatch = []; // Clear batch for next cycle
                }, 100); // Wait 100ms after last event to process batch
                
            } catch (error) {
                console.error('Error parsing event data:', error);
            }
        };

        eventSource.onerror = () => {
            setConnectionStatus('disconnected');
        };

        return () => {
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
        <div className="container">
            <div className="header">
                <h1>Event Stream Dashboard</h1>
            </div>
            <div className="status">
                Connection Status: 
                <span className={connectionStatus === 'connected' ? 'connected' : 'disconnected'}>
                    {connectionStatus === 'connected' ? ' ● Connected' : ' ● Disconnected'}
                </span>
                {events.length > 0 && <span> | Events: {events.length}</span>}
            </div>
            <div className="nav">
                <a href="/create.html">+ Create New Event</a>
                <a href="/search.html">Search Events</a>
                <a href="http://localhost:8082/permissions.html" target="_blank">Manage Permissions ↗</a>
            </div>
            <div className="table-container">
                {events.length === 0 ? (
                    <div className="empty-state">
                        <p>No events received yet. <a href="/create.html">Create your first event</a> or make sure the database has events and the connection is established.</p>
                    </div>
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
                            {events.map((event) => (
                                <tr key={event.id}>
                                    <td className="event-id">{event.id}</td>
                                    <td className="timestamp">{formatTimestamp(event.timestamp)}</td>
                                    <td className="title">{event.title}</td>
                                    <td className="description">{event.description}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                )}
            </div>
        </div>
    );
}

ReactDOM.render(<EventDashboard />, document.getElementById('root'));