const { useState, useEffect } = React;

function SearchApp() {
    const [query, setQuery] = useState('');
    const [userId, setUserId] = useState('user1');
    const [results, setResults] = useState([]);
    const [isLoading, setIsLoading] = useState(false);
    const [error, setError] = useState('');

    const handleSearch = async (e) => {
        if (e) e.preventDefault();

        if (!userId) {
            setError('Please select a user ID');
            return;
        }

        setIsLoading(true);
        setError('');
        setResults([]);

        await performRegularSearch();
    };

    const performRegularSearch = async () => {
        try {
            const url = new URL('/api/rpc/v1/search', window.location.origin);

            const response = await fetch(url, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Accept': 'application/x-ndjson'
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

                // Process all complete lines
                buffer = lines.pop(); // Keep the last partial line in buffer

                const newEvents = lines
                    .filter(line => line.trim())
                    .map(line => JSON.parse(line));

                if (newEvents.length > 0) {
                    setResults(prev => [...prev, ...newEvents]);
                }
            }

            // Process any remaining data
            if (buffer && buffer.trim()) {
                setResults(prev => [...prev, JSON.parse(buffer)]);
            }

        } catch (err) {
            setError(err.message);
        } finally {
            setIsLoading(false);
        }
    };


    const clearSearch = () => {
        setQuery('');
        setResults([]);
        setError('');
        setIsLoading(false);
    };

    const formatDate = (dateString) => {
        return new Date(dateString).toLocaleString();
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
                        </div>
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
        </>
    );
}

ReactDOM.render(<SearchApp />, document.getElementById('root'));