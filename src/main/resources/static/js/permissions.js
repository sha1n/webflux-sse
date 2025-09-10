const { useState } = React;

function PermissionManagement() {
    const [formData, setFormData] = useState({
        userId: '',
        eventIds: ''
    });
    const [status, setStatus] = useState({ type: '', message: '' });
    const [isSubmitting, setIsSubmitting] = useState(false);

    const handleInputChange = (e) => {
        const { name, value } = e.target;
        setFormData(prev => ({
            ...prev,
            [name]: name === 'eventIds' ? value : value.trim()
        }));
    };

    const handleSubmit = async (e) => {
        e.preventDefault();
        
        // Validation
        if (!formData.userId || !formData.eventIds) {
            setStatus({
                type: 'error',
                message: 'Please fill in both User ID and Event ID(s)'
            });
            return;
        }

        // Parse event IDs (supports both single and comma-separated)
        const eventIdsInput = formData.eventIds.trim();
        const eventIds = eventIdsInput.includes(',') 
            ? eventIdsInput.split(',').map(id => id.trim()).filter(id => id)
            : [eventIdsInput];

        // Validate all event IDs are valid numbers
        const validEventIds = [];
        for (const id of eventIds) {
            const num = parseInt(id);
            if (isNaN(num) || num <= 0) {
                setStatus({
                    type: 'error',
                    message: `Invalid event ID: "${id}". Event IDs must be positive numbers.`
                });
                return;
            }
            validEventIds.push(num);
        }

        const isBulk = validEventIds.length > 1;
        setIsSubmitting(true);
        setStatus({ 
            type: 'info', 
            message: isBulk ? `Creating ${validEventIds.length} permissions...` : 'Creating permission...'
        });

        try {
            if (isBulk) {
                // Use bulk API for multiple IDs
                const response = await fetch('/api/permissions/bulk', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    body: JSON.stringify({
                        userId: formData.userId,
                        eventIds: validEventIds
                    }),
                });

                if (response.ok) {
                    const result = await response.json();
                    const skipped = validEventIds.length - result.length;
                    setStatus({
                        type: 'success',
                        message: `${result.length} permissions created successfully!${skipped > 0 ? ` (${skipped} already existed)` : ''}`
                    });
                } else {
                    const errorText = await response.text();
                    setStatus({
                        type: 'error',
                        message: `Failed to create permissions: ${response.status} - ${errorText}`
                    });
                }
            } else {
                // Use single API for one ID
                const response = await fetch('/api/permissions', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    body: JSON.stringify({
                        userId: formData.userId,
                        eventId: validEventIds[0]
                    }),
                });

                if (response.ok) {
                    const result = await response.json();
                    setStatus({
                        type: 'success',
                        message: `Permission created successfully! Permission ID: ${result.id}`
                    });
                } else {
                    const errorText = await response.text();
                    setStatus({
                        type: 'error',
                        message: `Failed to create permission: ${response.status} - ${errorText}`
                    });
                }
            }

            // Reset form on success
            setFormData({ userId: '', eventIds: '' });
        } catch (error) {
            setStatus({
                type: 'error',
                message: `Error creating permission(s): ${error.message}`
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
                <p>Grant a user access to view one or multiple events</p>

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
                        <label htmlFor="eventIds">Event ID(s)</label>
                        <input
                            type="text"
                            id="eventIds"
                            name="eventIds"
                            value={formData.eventIds}
                            onChange={handleInputChange}
                            placeholder="Enter event ID or multiple IDs separated by commas (e.g., 8 or 1, 2, 3)"
                            disabled={isSubmitting}
                            required
                        />
                        <div className="form-help">
                            Enter a single event ID (e.g., 8) or multiple IDs separated by commas (e.g., 1, 2, 3)
                        </div>
                    </div>

                    <div className="form-actions">
                        <button 
                            type="submit" 
                            className="btn-submit"
                            disabled={isSubmitting}
                        >
                            {isSubmitting ? 'Creating Permission(s)...' : 'Create Permission(s)'}
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