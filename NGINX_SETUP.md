# Nginx Gateway Setup

## Overview
Added Nginx as a reverse proxy gateway to provide a unified entry point for the application.

## What Was Added

### 1. Nginx Configuration (`nginx/nginx.conf`)
- **Static file serving** from `/usr/share/nginx/html`
- **Reverse proxy rules**:
  - `/api/v1/events` → search-service:8081
  - `/api/rpc/v1/search` → search-service:8081
  - `/api/v1/permissions` → authorization-service:8082
  - `/swagger-ui.html` → appropriate backend service
- **SSE support** with disabled buffering and long timeouts
- **Health check** endpoint at `/health`
- **Gzip compression** for static assets

### 2. Static File Preparation Script (`prepare-nginx-static.sh`)
- Collects static files from both services
- Merges them into `nginx/html/` directory
- Run automatically by `start.sh`

### 3. Docker Compose Updates (`docker-compose.yml`)
- Added `nginx` service using `nginx:alpine` image
- Configured with:
  - Port 80 mapped to host
  - Volume mounts for config and static files
  - `host.docker.internal` for backend access
  - Health check monitoring

### 4. Updated UI Files
- **dashboard.js**: Changed permissions link from `localhost:8082` to `/permissions.html`
- **permissions.js**: Changed events link from `localhost:8081` to `/`
- All pages now use relative paths for cross-service navigation

### 5. Updated Start Script (`start.sh`)
- Runs `prepare-nginx-static.sh` before starting containers
- Updated health checks to use versioned API paths (`/api/v1/...`)
- Updated info display to show Nginx as main entry point

### 6. Documentation
- Added Nginx Gateway section to `README.md`
- Created this setup guide

## Architecture

```
Browser (port 80)
    ↓
Nginx Gateway
    ├─→ Static Files (HTML/CSS/JS)
    ├─→ /api/v1/events → search-service:8081
    ├─→ /api/rpc/v1/search → search-service:8081
    └─→ /api/v1/permissions → authorization-service:8082
```

## Usage

### Starting the Application
```bash
./start.sh
```

This will:
1. Prepare static files for Nginx
2. Start Docker containers (PostgreSQL, Elasticsearch, Nginx)
3. Start backend services (search-service, authorization-service)

### Accessing the Application
- **Main UI**: http://localhost
- **Search**: http://localhost/search.html
- **Permissions**: http://localhost/permissions.html
- **Health Check**: http://localhost/health

### Direct Backend Access (Optional)
- Search Service: http://localhost:8081
- Authorization Service: http://localhost:8082

### Updating Static Files
If you modify UI files:
```bash
./prepare-nginx-static.sh
docker-compose restart nginx
```

## Files Created/Modified

### New Files
- `nginx/nginx.conf` - Nginx configuration
- `nginx/html/` - Static files directory (auto-generated, in .gitignore)
- `prepare-nginx-static.sh` - Static file preparation script
- `NGINX_SETUP.md` - This file

### Modified Files
- `docker-compose.yml` - Added nginx service
- `start.sh` - Added nginx preparation
- `search-service/.../dashboard.js` - Updated navigation links
- `authorization-service/.../permissions.js` - Updated navigation links
- `.gitignore` - Added `nginx/html/`
- `README.md` - Added Nginx Gateway section

## Benefits

1. **Unified Entry Point**: Single port (80) for all services
2. **Production-Ready**: Nginx serves static files efficiently
3. **Clean URLs**: No port numbers in browser URLs
4. **SSE Optimization**: Proper configuration for real-time streaming
5. **Easy Deployment**: Ready for containerization with Docker

## Testing

Start the application and verify:
```bash
# Test health endpoint
curl http://localhost/health

# Test static files
curl http://localhost/

# Test API routing
curl http://localhost/api/v1/events

# Test SSE streaming
curl -N -H "Accept: text/event-stream" http://localhost/api/v1/events
```
