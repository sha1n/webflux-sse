# Nginx UI and Gateway

This directory contains the Nginx configuration and all UI files for the WebFlux SSE application.

## Directory Structure

```
nginx/
├── nginx.conf          # Nginx configuration (reverse proxy + static file serving)
├── html/               # UI files (HTML, CSS, JavaScript)
│   ├── index.html      # Event Stream Dashboard (main page)
│   ├── create.html     # Create New Event page
│   ├── search.html     # Event Search page
│   ├── permissions.html # Permission Management page
│   ├── css/            # Stylesheets
│   │   ├── common.css      # Shared styles across all pages
│   │   ├── dashboard.css   # Dashboard-specific styles
│   │   ├── create-form.css # Create form styles
│   │   ├── search.css      # Search page styles
│   │   └── permissions.css # Permissions page styles
│   └── js/             # JavaScript files (React components)
│       ├── dashboard.js    # Event stream dashboard logic
│       ├── create-form.js  # Event creation form
│       ├── search.js       # Search functionality
│       └── permissions.js  # Permission management
└── README.md           # This file

```

## Nginx Configuration

The `nginx.conf` file provides:

1. **Static File Serving**: All HTML, CSS, and JavaScript files from `html/`
2. **Reverse Proxy**: Routes API requests to backend services
   - `/api/v1/events` → search-server (port 8081)
   - `/api/rpc/v1/search` → search-server (port 8081)
   - `/api/v1/permissions` → authorization-server (port 8082)
3. **Swagger UI Routing**:
   - `/search-api-docs` → search-server Swagger UI
   - `/auth-api-docs` → authorization-server Swagger UI
4. **SSE Support**: Proper configuration for Server-Sent Events streaming

## UI Pages

### Event Stream Dashboard (`index.html`)
- Real-time event streaming using Server-Sent Events (SSE)
- Displays events as they arrive from the database
- Links to create, search, and permissions pages

### Create Event (`create.html`)
- Form to create new events with title and description
- Events appear in the dashboard stream within 2 seconds

### Search Events (`search.html`)
- Permission-aware search functionality
- Filters events based on user permissions
- Supports full-text search via Elasticsearch

### Permission Management (`permissions.html`)
- Manage user permissions for viewing events
- Add/remove permissions per user
- Bulk permission creation support

## Making Changes

To modify the UI:

1. Edit files in `nginx/html/` directly
2. Changes are served immediately by nginx (no build step required)
3. Use browser refresh to see updates

The backend services (search-server and authorization-server) do NOT contain any UI files - this nginx directory is the single source of truth for all UI code.

## Deployment

The nginx container mounts this directory:
- Configuration: `./nginx/nginx.conf` → `/etc/nginx/nginx.conf`
- Static files: `./nginx/html/` → `/usr/share/nginx/html/`

Access the UI at: http://localhost
