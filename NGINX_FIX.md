# Nginx 502 Error Fix

## Problem
Nginx was returning 502 Bad Gateway errors when trying to proxy requests to the backend services. The UI loaded fine, but all API calls failed.

## Root Cause
The issue was that `host.docker.internal` (configured in docker-compose.yml with `extra_hosts`) wasn't routing traffic correctly from the nginx container to the host machine where the Spring Boot services were running.

### Details:
- `host.docker.internal` resolved to DNS correctly (e.g., `192.168.5.2`)
- However, connections failed with "Connection refused"
- The Docker gateway IP (`172.20.0.1`) also didn't work
- Direct connection to the resolved IP (`192.168.5.2`) worked

## Solution
Created an automated configuration script that detects the correct Docker host IP and updates nginx.conf before starting services.

### Implementation:

1. **Created `configure-nginx-host.sh`**:
   - Automatically detects the Docker host IP based on the OS
   - On macOS: Uses `ipconfig getifaddr` to get the primary interface IP
   - On Linux: Uses `ip` command to get the Docker bridge IP
   - Updates nginx.conf with the correct IP address

2. **Updated `start.sh`**:
   - Runs `configure-nginx-host.sh` before starting Docker containers
   - Ensures nginx always has the correct host IP configuration

3. **Updated nginx.conf**:
   - Changed from `host.docker.internal` to the actual host IP (e.g., `192.168.5.2`)
   - This is now automatically configured on each startup

## Testing
After the fix, all API endpoints work correctly through nginx:

```bash
# Test events API
curl http://localhost/api/v1/events
# Returns: []

# Test permissions API
curl http://localhost/api/v1/permissions
# Returns: []

# Test POST request
curl -X POST http://localhost/api/v1/events \
  -H "Content-Type: application/json" \
  -d '{"title":"Test","description":"Testing"}'
# Returns: {"id":1,"timestamp":"...","title":"Test","description":"Testing"}
```

## Why host.docker.internal Failed
On some Docker installations (especially older versions or certain configurations), `host.docker.internal` may not route traffic correctly even though it resolves via DNS. This is a known issue with Docker networking.

## Benefits of the Solution
1. **Automatic**: Detects the correct IP on each startup
2. **Cross-platform**: Works on both macOS and Linux
3. **No manual configuration**: Users don't need to know their Docker host IP
4. **Reliable**: Uses the actual network interface IP instead of DNS aliases

## Future Improvements
If you want to make this even more robust, consider:
1. Running both Spring Boot services in Docker containers on the same network as nginx
2. Using Docker Compose service names for networking (e.g., `search-service:8081`)
3. This would eliminate the need for host IP detection entirely

## Files Modified
- `nginx/nginx.conf` - Updated with actual host IP (auto-configured)
- `configure-nginx-host.sh` - New script to detect and configure host IP
- `start.sh` - Updated to run configuration script before starting services
