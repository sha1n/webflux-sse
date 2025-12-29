const { useState, useEffect } = React;

function PermissionManagement() {
    const [formData, setFormData] = useState({
        userId: '',
        eventIds: ''
    });
    const [status, setStatus] = useState({ type: '', message: '' });
    const [isSubmitting, setIsSubmitting] = useState(false);
    const [showModal, setShowModal] = useState(false);
    const [permissions, setPermissions] = useState([]);
    const [isLoadingPermissions, setIsLoadingPermissions] = useState(true);

    // Fetch all permissions
    const fetchPermissions = async () => {
        setIsLoadingPermissions(true);
        try {
            const response = await fetch('/api/v1/permissions');
            if (response.ok) {
                const data = await response.json();
                setPermissions(data);
            } else {
                console.error('Failed to fetch permissions:', response.status);
            }
        } catch (error) {
            console.error('Error fetching permissions:', error);
        } finally {
            setIsLoadingPermissions(false);
        }
    };

    // Load permissions on mount
    useEffect(() => {
        fetchPermissions();
    }, []);

    // Group permissions by user
    const groupedPermissions = permissions.reduce((acc, permission) => {
        const userId = permission.userId;
        if (!acc[userId]) {
            acc[userId] = [];
        }
        acc[userId].push(permission.eventId);
        return acc;
    }, {});

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

        try {
            if (isBulk) {
                // Use bulk API for multiple IDs
                const response = await fetch('/api/v1/permissions/bulk', {
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

                    // Close modal immediately
                    setShowModal(false);
                    setFormData({ userId: '', eventIds: '' });

                    // Show success message on main page
                    setStatus({
                        type: 'success',
                        message: `${result.length} permissions created successfully!${skipped > 0 ? ` (${skipped} already existed)` : ''}`
                    });

                    // Auto-clear message after 5 seconds
                    setTimeout(() => {
                        setStatus({ type: '', message: '' });
                    }, 5000);

                    // Refresh permissions list
                    fetchPermissions();
                } else {
                    const errorText = await response.text();
                    setStatus({
                        type: 'error',
                        message: `Failed to create permissions: ${response.status} - ${errorText}`
                    });
                }
            } else {
                // Use single API for one ID
                const response = await fetch('/api/v1/permissions', {
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

                    // Close modal immediately
                    setShowModal(false);
                    setFormData({ userId: '', eventIds: '' });

                    // Show success message on main page
                    setStatus({
                        type: 'success',
                        message: `Permission created successfully! Permission ID: ${result.id}`
                    });

                    // Auto-clear message after 5 seconds
                    setTimeout(() => {
                        setStatus({ type: '', message: '' });
                    }, 5000);

                    // Refresh permissions list
                    fetchPermissions();
                } else {
                    const errorText = await response.text();
                    setStatus({
                        type: 'error',
                        message: `Failed to create permission: ${response.status} - ${errorText}`
                    });
                }
            }
        } catch (error) {
            setStatus({
                type: 'error',
                message: `Error creating permission(s): ${error.message}`
            });
        } finally {
            setIsSubmitting(false);
        }
    };

    const handleOpenModal = (userId = '') => {
        setFormData({ userId, eventIds: '' });
        setStatus({ type: '', message: '' });
        setShowModal(true);
    };

    const handleCloseModal = () => {
        setShowModal(false);
        setStatus({ type: '', message: '' });
    };

    const handleDeletePermission = async (userId, eventId) => {
        if (!confirm(`Delete permission for user "${userId}" on event ${eventId}?`)) {
            return;
        }

        try {
            const response = await fetch(`/api/v1/permissions/event/${eventId}/user/${userId}`, {
                method: 'DELETE'
            });

            if (response.ok) {
                // Refresh permissions list
                fetchPermissions();
            } else {
                alert(`Failed to delete permission: ${response.status}`);
            }
        } catch (error) {
            alert(`Error deleting permission: ${error.message}`);
        }
    };

    return (
        <>
            <div className="api-docs-ribbon">
                <a href="/auth-docs/swagger-ui.html" target="_blank">
                    API Docs
                </a>
            </div>
            <div className="container">
                <div className="header">
                    <h1>Permission Management</h1>
                </div>

                <div className="nav">
                    <a href="/">Dashboard</a>
                    <a href="/create.html" className="primary">+ Create Event</a>
                    <a href="/search.html">Search (Stream)</a>
                    <a href="/search-sse.html">Search (SSE)</a>
                    <span className="current">Permissions</span>
                </div>

                <div className="permissions-content">
                <div className="toolbar">
                    <h2>User Permissions ({Object.keys(groupedPermissions).length} users)</h2>
                    <button
                        className="btn-add"
                        onClick={() => handleOpenModal()}
                    >
                        + Add Permission
                    </button>
                </div>

                {status.message && (
                    <div className={`status-message status-${status.type}`}>
                        {status.message}
                    </div>
                )}

                {isLoadingPermissions ? (
                    <div className="loading-state">Loading permissions...</div>
                ) : permissions.length === 0 ? (
                    <div className="empty-state">
                        <p>No permissions configured yet.</p>
                        <button className="btn-submit" onClick={() => handleOpenModal()}>
                            Create First Permission
                        </button>
                    </div>
                ) : (
                    <div className="permissions-table-container">
                        <table className="permissions-table">
                            <thead>
                                <tr>
                                    <th>User ID</th>
                                    <th>Permitted Event IDs</th>
                                    <th>Count</th>
                                    <th>Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                {Object.entries(groupedPermissions)
                                    .sort(([userA], [userB]) => userA.localeCompare(userB))
                                    .map(([userId, eventIds]) => (
                                    <tr key={userId}>
                                        <td className="user-id">{userId}</td>
                                        <td className="event-ids">
                                            {eventIds.sort((a, b) => a - b).map(eventId => (
                                                <span key={eventId} className="event-id-badge">
                                                    {eventId}
                                                    <button
                                                        className="delete-badge-btn"
                                                        onClick={() => handleDeletePermission(userId, eventId)}
                                                        title="Remove permission"
                                                    >
                                                        ×
                                                    </button>
                                                </span>
                                            ))}
                                        </td>
                                        <td className="count">{eventIds.length}</td>
                                        <td className="actions">
                                            <button
                                                className="btn-action"
                                                onClick={() => handleOpenModal(userId)}
                                            >
                                                Add More
                                            </button>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                )}
            </div>

            {/* Modal for adding permissions */}
            {showModal && (
                <div className="modal-overlay" onClick={handleCloseModal}>
                    <div className="modal-content" onClick={(e) => e.stopPropagation()}>
                        <div className="modal-header">
                            <h2>Add User Permission</h2>
                            <button
                                className="modal-close"
                                onClick={handleCloseModal}
                            >
                                ×
                            </button>
                        </div>

                        <div className="modal-body">
                            <p className="modal-description">
                                Grant a user access to view one or multiple events
                            </p>

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
                                    <button
                                        type="button"
                                        className="btn-cancel"
                                        onClick={handleCloseModal}
                                        disabled={isSubmitting}
                                    >
                                        Cancel
                                    </button>
                                </div>
                            </form>
                        </div>
                    </div>
                </div>
            )}
        </div>
        </>
    );
}

const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(<PermissionManagement />);
