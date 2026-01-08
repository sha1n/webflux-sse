document.addEventListener('DOMContentLoaded', () => {
    const userIdInput = document.getElementById('userIdInput');
    const fetchPermissionsBtn = document.getElementById('fetchPermissionsBtn');
    const fetchPermissionsVtBtn = document.getElementById('fetchPermissionsVtBtn');
    const displayUserIdSpan = document.getElementById('displayUserId');
    const authorizedCountSpan = document.getElementById('authorizedCount');
    const eventIdsList = document.getElementById('eventIdsList');
    const resultsContainer = document.getElementById('resultsContainer');

    // Function to fetch and display permissions
    async function fetchAndDisplayPermissions(serviceBaseUrl) {
        const userId = userIdInput.value.trim();
        if (!userId) {
            alert('Please enter a User ID.');
            return;
        }

        displayUserIdSpan.textContent = userId;
        authorizedCountSpan.textContent = 'Loading...';
        eventIdsList.innerHTML = '<li>Loading...</li>';
        resultsContainer.style.display = 'block';

        try {
            const response = await fetch(`${serviceBaseUrl}/api/v1/user-permissions/${userId}`);
            if (!response.ok) {
                const errorText = await response.text();
                throw new Error(`HTTP error! status: ${response.status}, message: ${errorText}`);
            }
            const data = await response.json();

            authorizedCountSpan.textContent = data.count;
            eventIdsList.innerHTML = ''; // Clear previous list

            if (data.eventIds && data.eventIds.length > 0) {
                data.eventIds.forEach(id => {
                    const listItem = document.createElement('li');
                    listItem.textContent = id;
                    eventIdsList.appendChild(listItem);
                });
            } else {
                const listItem = document.createElement('li');
                listItem.textContent = 'No authorized events found.';
                eventIdsList.appendChild(listItem);
            }
        } catch (error) {
            console.error('Error fetching user permissions:', error);
            authorizedCountSpan.textContent = 'Error';
            eventIdsList.innerHTML = `<li>Error: ${error.message}</li>`;
        }
    }

    // Event listener for WebFlux button
    fetchPermissionsBtn.addEventListener('click', () => fetchAndDisplayPermissions('http://localhost:8081'));

    // Event listener for Virtual Threads button
    fetchPermissionsVtBtn.addEventListener('click', () => fetchAndDisplayPermissions('http://localhost:8083'));

    // Optionally, load permissions for default user on page load
    fetchAndDisplayPermissions('http://localhost:8081'); // Default to WebFlux on load
});
