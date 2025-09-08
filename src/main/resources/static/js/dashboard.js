const { useState, useEffect } = React;

function EventDashboard() {
    const [events, setEvents] = useState([]);
    const [connectionStatus, setConnectionStatus] = useState('disconnected');

    useEffect(() => {
        const eventSource = new EventSource('/api/events/stream');

        eventSource.onopen = () => {
            setConnectionStatus('connected');
        };

        eventSource.onmessage = (event) => {
            try {
                const newEvent = JSON.parse(event.data);
                setEvents(prevEvents => {
                    const exists = prevEvents.some(e => e.id === newEvent.id);
                    if (!exists) {
                        return [newEvent, ...prevEvents].slice(0, 100);
                    }
                    return prevEvents;
                });
            } catch (error) {
                console.error('Error parsing event data:', error);
            }
        };

        eventSource.onerror = () => {
            setConnectionStatus('disconnected');
        };

        return () => {
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
                                <th>Timestamp</th>
                                <th>Title</th>
                                <th>Description</th>
                            </tr>
                        </thead>
                        <tbody>
                            {events.map((event) => (
                                <tr key={event.id}>
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