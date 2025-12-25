const { useState } = React;

function CreateEventForm() {
    const [title, setTitle] = useState('');
    const [description, setDescription] = useState('');
    const [isSubmitting, setIsSubmitting] = useState(false);
    const [message, setMessage] = useState('');
    const [messageType, setMessageType] = useState(''); // 'success' or 'error'

    const handleSubmit = async (e) => {
        e.preventDefault();
        
        if (!title.trim()) {
            setMessage('Title is required');
            setMessageType('error');
            return;
        }

        setIsSubmitting(true);
        setMessage('');

        try {
            const response = await fetch('/api/v1/events', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    title: title.trim(),
                    description: description.trim()
                }),
            });

            if (response.ok) {
                const createdEvent = await response.json();
                setTitle('');
                setDescription('');
                setMessage(`Event "${createdEvent.title}" created successfully! It should appear in the dashboard stream within 2 seconds.`);
                setMessageType('success');
                setTimeout(() => {
                    setMessage('');
                    setMessageType('');
                }, 5000);
            } else {
                throw new Error(`Server responded with status ${response.status}`);
            }
        } catch (error) {
            console.error('Error creating event:', error);
            setMessage('Failed to create event. Please check your connection and try again.');
            setMessageType('error');
        } finally {
            setIsSubmitting(false);
        }
    };

    const handleReset = () => {
        setTitle('');
        setDescription('');
        setMessage('');
        setMessageType('');
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
                    <h1>Create New Event</h1>
                </div>
                <div className="nav">
                    <a href="/">← Back to Dashboard</a>
                    <span className="current">Create Event</span>
                </div>
            <div className="form-container">
                <p className="form-description">
                    Create a new event that will be immediately added to the database and appear in the real-time event stream. 
                    All connected clients will see your event within 2 seconds.
                </p>

                {message && (
                    <div className={messageType === 'success' ? 'success-message' : 'error-message'}>
                        {message}
                    </div>
                )}

                <form onSubmit={handleSubmit}>
                    <div className="form-group">
                        <label htmlFor="title">
                            Event Title <span className="required">*</span>
                        </label>
                        <input
                            id="title"
                            type="text"
                            value={title}
                            onChange={(e) => setTitle(e.target.value)}
                            placeholder="Enter a descriptive title for your event"
                            disabled={isSubmitting}
                            maxLength="255"
                            required
                        />
                    </div>

                    <div className="form-group">
                        <label htmlFor="description">Description</label>
                        <textarea
                            id="description"
                            value={description}
                            onChange={(e) => setDescription(e.target.value)}
                            placeholder="Provide additional details about the event (optional)"
                            disabled={isSubmitting}
                            rows="4"
                        />
                    </div>

                    <div className="form-actions">
                        <button type="submit" className="btn btn-primary" disabled={isSubmitting}>
                            {isSubmitting ? 'Creating Event...' : 'Create Event'}
                        </button>
                        <button type="button" className="btn btn-secondary" onClick={handleReset} disabled={isSubmitting}>
                            Clear Form
                        </button>
                    </div>
                </form>
            </div>
        </div>
        </>
    );
}

ReactDOM.render(<CreateEventForm />, document.getElementById('root'));