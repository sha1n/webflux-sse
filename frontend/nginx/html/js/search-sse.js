const { useState, useEffect } = React;

function SearchApp() {
    const [query, setQuery] = useState('');
    const [userId, setUserId] = useState('user1');
    const [backend, setBackend] = useState('reactive');
    const [results, setResults] = useState([]);
    const [isLoading, setIsLoading] = useState(false);
    const [error, setError] = useState('');
    const [timingStats, setTimingStats] = useState(null);
    const [apiEndpoint, setApiEndpoint] = useState('');

    const handleSearch = async (e) => {
        if (e) e.preventDefault();

        if (!userId) {
            setError('Please select a user ID');
            return;
        }

        setIsLoading(true);
        setError('');
        setResults([]);

        await performSseSearch();
    };

    const performSseSearch = async () => {
        const startTime = performance.now();
        try {
            const apiPath = backend === 'reactive'
                ? '/api-reactive/rpc/v1/search'
                : '/api-virtual/rpc/v1/search';
            const url = new URL(apiPath, window.location.origin);
            setApiEndpoint(`POST ${apiPath}`);

            const response = await fetch(url, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Accept': 'text/event-stream'
                },
                body: JSON.stringify({
                    query: query || null,
                    userId: userId
                })
            });

            if (!response.ok) {
                throw new Error(`Search failed: ${response.status} ${response.statusText}`);
            }

            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let buffer = '';

            while (true) {
                const { done, value } = await reader.read();
                if (done) break;

                buffer += decoder.decode(value, { stream: true });
                const lines = buffer.split('\n');

                // Keep the last partial line in buffer
                buffer = lines.pop() || '';

                // Process SSE format: look for 'data:' lines
                for (const line of lines) {
                    if (line.startsWith('data:')) {
                        const jsonData = line.substring(5).trim(); // Remove 'data:' prefix
                        if (jsonData) {
                            try {
                                const event = JSON.parse(jsonData);
                                setResults(prev => [...prev, event]);
                            } catch (e) {
                                console.error('Failed to parse SSE data:', e, jsonData);
                            }
                        }
                    }
                }
            }

            // Process any remaining data in buffer
            if (buffer.trim()) {
                const lines = buffer.split('\n');
                for (const line of lines) {
                    if (line.startsWith('data:')) {
                        const jsonData = line.substring(5).trim();
                        if (jsonData) {
                            try {
                                const event = JSON.parse(jsonData);
                                setResults(prev => [...prev, event]);
                            } catch (e) {
                                console.error('Failed to parse SSE data:', e, jsonData);
                            }
                        }
                    }
                }
            }

        } catch (err) {
            setError(err.message);
        } finally {
            const endTime = performance.now();
            const elapsedMs = Math.round(endTime - startTime);
            setTimingStats({ elapsedMs });
            setIsLoading(false);
        }
    };


    const clearSearch = () => {
        setQuery('');
        setResults([]);
        setError('');
        setIsLoading(false);
        setTimingStats(null);
    };

    const formatDate = (dateString) => {
        return new Date(dateString).toLocaleString();
    };


    return (
        <>
            <div className="api-docs-ribbon">
                <a href={backend === 'reactive' ? '/search-docs/swagger-ui.html' : '/search-virtual-docs/swagger-ui.html'} target="_blank">
                    API Docs ({backend === 'reactive' ? 'Reactive' : 'Virtual Threads'})
                </a>
            </div>
            <div className="container">
                <div className="header">
                    <h1>Event Search (SSE)</h1>
                </div>
                <div className="nav">
                    <a href="/">Dashboard</a>
                    <a href="/create.html" className="primary">+ Create Event</a>
                    <a href="/bulk-create.html">Bulk Create</a>
                    <a href="/search.html">Search (Stream)</a>
                    <span className="current">Search (SSE)</span>
                    <a href="/permissions.html">Permissions</a>
                    <a href="/user-permissions.html">User Permissions</a>
                </div>
            <div className="search-container">
                <form onSubmit={handleSearch} className="search-form">
                    <div className="search-row">
                        <div className="search-field">
                            <label htmlFor="query">Search Query</label>
                            <input
                                id="query"
                                type="text"
                                value={query}
                                onChange={(e) => setQuery(e.target.value)}
                                placeholder="Enter search terms (leave empty to see all permitted events)"
                                disabled={isLoading}
                            />
                        </div>
                        <div className="search-field" style={{ minWidth: '150px', flex: '0 0 150px' }}>
                            <label htmlFor="userId">User ID</label>
                            <select
                                id="userId"
                                value={userId}
                                onChange={(e) => setUserId(e.target.value)}
                                disabled={isLoading}
                            >
                                <option value="">Select User...</option>
                                <option value="user1">user1</option>
                                <option value="user2">user2</option>
                                <option value="user3">user3</option>
                                <option value="admin">admin</option>
                            </select>
                        </div>
                        <div className="search-field" style={{ minWidth: '150px', flex: '0 0 150px' }}>
                            <label style={{ display: 'block', marginBottom: '8px' }}>Backend</label>
                            <div style={{ display: 'flex', gap: '15px', alignItems: 'center', height: '38px' }}>
                                <label style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', margin: 0 }}>
                                    <input
                                        type="radio"
                                        name="backend"
                                        value="reactive"
                                        checked={backend === 'reactive'}
                                        onChange={(e) => setBackend(e.target.value)}
                                        disabled={isLoading}
                                        style={{ marginRight: '5px', cursor: 'pointer' }}
                                    />
                                    WF
                                </label>
                                <label style={{ display: 'flex', alignItems: 'center', cursor: 'pointer', margin: 0 }}>
                                    <input
                                        type="radio"
                                        name="backend"
                                        value="virtual"
                                        checked={backend === 'virtual'}
                                        onChange={(e) => setBackend(e.target.value)}
                                        disabled={isLoading}
                                        style={{ marginRight: '5px', cursor: 'pointer' }}
                                    />
                                    VT
                                </label>
                            </div>
                        </div>
                        <div className="search-actions">
                            <button type="submit" className="btn-search" disabled={isLoading || !userId}>
                                {isLoading ? 'Searching...' : 'Search'}
                            </button>
                            <button type="button" className="btn-clear" onClick={clearSearch} disabled={isLoading}>
                                Clear
                            </button>
                        </div>
                    </div>
                </form>

                {error && (
                    <div className="error-message">
                        {error}
                    </div>
                )}

                <div className="results-container">
                    <div className="results-header">
                        <div className="results-count">
                            {results.length} event{results.length !== 1 ? 's' : ''} found
                            {userId && ` for user: ${userId}`}
                            {timingStats && <span style={{marginLeft: '10px'}}><strong>{timingStats.elapsedMs}ms</strong></span>}
                            {apiEndpoint && <span style={{marginLeft: '10px', fontSize: '0.9em', color: '#666'}}>({apiEndpoint})</span>}
                        </div>
                    </div>

                    {isLoading && (
                        <div className="loading">
                            Searching events via SSE...
                        </div>
                    )}

                    {!isLoading && results.length === 0 && !error && (
                        <div className="no-results">
                            {userId ? `No events found for user ${userId}` : 'No events found'}
                            <br />
                            <small>Make sure the user has permissions on some events</small>
                        </div>
                    )}

                    {results.length > 0 && (
                        <div className="document-grid">
                            {results.map((event) => (
                                <div key={event.id} className="document-card">
                                    <div className="document-title">{event.title}</div>
                                    <div className="document-content">{event.description}</div>
                                    <div className="document-meta">
                                        <span className="document-id">ID: {event.id}</span>
                                        <span>{formatDate(event.timestamp)}</span>
                                    </div>
                                </div>
                            ))}
                        </div>
                    )}
                </div>
            </div>
        </div>
        </>
    );
}

const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(<SearchApp />);
