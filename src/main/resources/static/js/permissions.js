const { useState } = React;

function PermissionManagement() {
    const [formData, setFormData] = useState({
        userId: '',
        eventId: ''
    });
    const [status, setStatus] = useState({ type: '', message: '' });
    const [isSubmitting, setIsSubmitting] = useState(false);

    const handleInputChange = (e) => {
        const { name, value } = e.target;
        setFormData(prev => ({
            ...prev,
            [name]: value.trim()
        }));
    };

    const handleSubmit = async (e) => {
        e.preventDefault();
        
        // Validation
        if (!formData.userId || !formData.eventId) {
            setStatus({
                type: 'error',
                message: 'Please fill in both User ID and Event ID'
            });
            return;
        }

        const eventIdNum = parseInt(formData.eventId);
        if (isNaN(eventIdNum) || eventIdNum <= 0) {
            setStatus({
                type: 'error',
                message: 'Event ID must be a positive number'
            });
            return;
        }

        setIsSubmitting(true);
        setStatus({ type: 'info', message: 'Creating permission...' });

        try {
            const response = await fetch('/api/permissions', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({
                    userId: formData.userId,
                    eventId: eventIdNum
                }),
            });

            if (response.ok) {
                const result = await response.json();
                setStatus({
                    type: 'success',
                    message: `Permission created successfully! Permission ID: ${result.id}`
                });
                setFormData({ userId: '', eventId: '' });
            } else {
                const errorText = await response.text();
                setStatus({
                    type: 'error',
                    message: `Failed to create permission: ${response.status} - ${errorText}`
                });
            }
        } catch (error) {
            setStatus({
                type: 'error',
                message: `Error creating permission: ${error.message}`
            });
        } finally {
            setIsSubmitting(false);
        }
    };

    return (
        <div className="container">
            <div className="header">
                <h1>Permission Management</h1>
            </div>
            
            <div className="nav-breadcrumb">
                <a href="/index.html">← Back to Dashboard</a>
            </div>

            <div className="form-container">
                <h2>Add User Permission</h2>
                <p>Grant a user access to view a specific event</p>

                {status.message && (
                    <div className={`status-message status-${status.type}`}>
                        {status.message}
                    </div>
                )}

                <form onSubmit={handleSubmit}>
                    <div className="form-group">
                        <label htmlFor="userId">User ID</label>
                        <input
                            type="text"
                            id="userId"
                            name="userId"
                            value={formData.userId}
                            onChange={handleInputChange}
                            placeholder="Enter user identifier (e.g., user123)"
                            disabled={isSubmitting}
                            required
                        />
                        <div className="form-help">
                            Enter the unique identifier for the user
                        </div>
                    </div>

                    <div className="form-group">
                        <label htmlFor="eventId">Event ID</label>
                        <input
                            type="number"
                            id="eventId"
                            name="eventId"
                            value={formData.eventId}
                            onChange={handleInputChange}
                            placeholder="Enter event ID (e.g., 8)"
                            min="1"
                            disabled={isSubmitting}
                            required
                        />
                        <div className="form-help">
                            Enter the ID of the event from the dashboard
                        </div>
                    </div>

                    <div className="form-actions">
                        <button 
                            type="submit" 
                            className="btn-submit"
                            disabled={isSubmitting}
                        >
                            {isSubmitting ? 'Creating...' : 'Create Permission'}
                        </button>
                        <a href="/index.html" className="btn-cancel">
                            Cancel
                        </a>
                    </div>
                </form>
            </div>
        </div>
    );
}

ReactDOM.render(<PermissionManagement />, document.getElementById('root'));