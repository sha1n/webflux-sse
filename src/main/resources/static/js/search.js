const { useState, useEffect } = React;

function SearchApp() {
    const [query, setQuery] = useState('');
    const [userId, setUserId] = useState('user1');
    const [results, setResults] = useState([]);
    const [isLoading, setIsLoading] = useState(false);
    const [error, setError] = useState('');
    const [isStreaming, setIsStreaming] = useState(false);
    const [eventSource, setEventSource] = useState(null);

    const handleSearch = async (e) => {
        if (e) e.preventDefault();
        
        if (!userId) {
            setError('Please select a user ID');
            return;
        }

        // Close existing stream if any
        if (eventSource) {
            eventSource.close();
            setEventSource(null);
        }

        setIsLoading(true);
        setError('');
        setResults([]);

        if (isStreaming) {
            startStreamSearch();
        } else {
            await performRegularSearch();
        }
    };

    const performRegularSearch = async () => {
        try {
            const url = new URL('/api/search', window.location.origin);
            if (query) url.searchParams.set('q', query);
            
            const response = await fetch(url, {
                headers: {
                    'X-User-Id': userId,
                    'Accept': 'application/json'
                }
            });

            if (!response.ok) {
                throw new Error(`Search failed: ${response.status} ${response.statusText}`);
            }

            const data = await response.json();
            setResults(Array.isArray(data) ? data : []);
        } catch (err) {
            setError(err.message);
        } finally {
            setIsLoading(false);
        }
    };

    const startStreamSearch = () => {
        const url = new URL('/api/search/stream', window.location.origin);
        if (query) url.searchParams.set('q', query);

        const stream = new EventSource(url, {
            headers: {
                'X-User-Id': userId
            }
        });

        stream.onmessage = (event) => {
            try {
                const document = JSON.parse(event.data);
                setResults(prev => {
                    const exists = prev.some(doc => doc.id === document.id);
                    if (!exists) {
                        return [...prev, document];
                    }
                    return prev;
                });
                setIsLoading(false);
            } catch (err) {
                console.error('Error parsing stream data:', err);
            }
        };

        stream.onopen = () => {
            setIsLoading(false);
        };

        stream.onerror = () => {
            setError('Stream connection failed');
            setIsLoading(false);
            stream.close();
        };

        setEventSource(stream);
    };

    const clearSearch = () => {
        if (eventSource) {
            eventSource.close();
            setEventSource(null);
        }
        setQuery('');
        setResults([]);
        setError('');
        setIsLoading(false);
    };

    const formatDate = (dateString) => {
        return new Date(dateString).toLocaleString();
    };

    useEffect(() => {
        return () => {
            if (eventSource) {
                eventSource.close();
            }
        };
    }, [eventSource]);

    return (
        <div className="container">
            <div className="header">
                <h1>Event Search</h1>
            </div>
            <div className="nav">
                <a href="/">← Back to Events Dashboard</a>
                <span className="current">Search Events</span>
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
                        <div className="search-field" style={{minWidth: '150px', flex: '0 0 150px'}}>
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
                        <div className="search-actions">
                            <button type="submit" className="btn-search" disabled={isLoading || !userId}>
                                {isLoading ? 'Searching...' : 'Search'}
                            </button>
                            <button type="button" className="btn-clear" onClick={clearSearch} disabled={isLoading}>
                                Clear
                            </button>
                        </div>
                    </div>
                    <div style={{marginTop: '15px'}}>
                        <label className="stream-toggle">
                            <input
                                type="checkbox"
                                checked={isStreaming}
                                onChange={(e) => setIsStreaming(e.target.checked)}
                                disabled={isLoading}
                            />
                            Use streaming search (SSE)
                        </label>
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
                        </div>
                        {isStreaming && eventSource && (
                            <div style={{color: '#28a745', fontSize: '14px'}}>
                                ● Streaming active
                            </div>
                        )}
                    </div>

                    {isLoading && (
                        <div className="loading">
                            Searching events...
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
    );
}

ReactDOM.render(<SearchApp />, document.getElementById('root'));