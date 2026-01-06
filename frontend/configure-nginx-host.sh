#!/bin/bash

# Script to configure nginx with the correct Docker host IP
# This is needed because host.docker.internal doesn't always work reliably

set -e

echo "Configuring nginx with Docker host IP..."

# Detect the Docker host IP
if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS - get the primary network interface IP
    HOST_IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "host.docker.internal")
    echo "Detected macOS host IP: $HOST_IP"
else
    # Linux - try to get the Docker bridge IP
    HOST_IP=$(ip -4 addr show docker0 2>/dev/null | grep -oP '(?<=inet\s)\d+(\.\d+){3}' || echo "172.17.0.1")
    echo "Detected Linux host IP: $HOST_IP"
fi

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NGINX_DIR="$SCRIPT_DIR/nginx"

# Create nginx.conf from template if template exists
if [ -f "$NGINX_DIR/nginx.conf.template" ]; then
    echo "Using template to generate nginx.conf..."
    sed "s/DOCKER_HOST_IP/$HOST_IP/g" "$NGINX_DIR/nginx.conf.template" > "$NGINX_DIR/nginx.conf"
    echo "nginx.conf generated from template"
else
    # Update existing nginx.conf
    echo "Updating existing nginx.conf..."
    # Backup first
    cp "$NGINX_DIR/nginx.conf" "$NGINX_DIR/nginx.conf.bak"

    # Replace DOCKER_HOST_IP placeholder or any existing IP addresses in the upstream blocks
    if [[ "$OSTYPE" == "darwin"* ]]; then
        sed -i '' "s/server DOCKER_HOST_IP:8081;/server $HOST_IP:8081;/" "$NGINX_DIR/nginx.conf"
        sed -i '' "s/server DOCKER_HOST_IP:8082;/server $HOST_IP:8082;/" "$NGINX_DIR/nginx.conf"
        sed -i '' "s/server DOCKER_HOST_IP:8083;/server $HOST_IP:8083;/" "$NGINX_DIR/nginx.conf"
        sed -i '' "s/server [0-9.]*:8081;/server $HOST_IP:8081;/" "$NGINX_DIR/nginx.conf"
        sed -i '' "s/server [0-9.]*:8082;/server $HOST_IP:8082;/" "$NGINX_DIR/nginx.conf"
        sed -i '' "s/server [0-9.]*:8083;/server $HOST_IP:8083;/" "$NGINX_DIR/nginx.conf"
    else
        sed -i "s/server DOCKER_HOST_IP:8081;/server $HOST_IP:8081;/" "$NGINX_DIR/nginx.conf"
        sed -i "s/server DOCKER_HOST_IP:8082;/server $HOST_IP:8082;/" "$NGINX_DIR/nginx.conf"
        sed -i "s/server DOCKER_HOST_IP:8083;/server $HOST_IP:8083;/" "$NGINX_DIR/nginx.conf"
        sed -i "s/server [0-9.]*:8081;/server $HOST_IP:8081;/" "$NGINX_DIR/nginx.conf"
        sed -i "s/server [0-9.]*:8082;/server $HOST_IP:8082;/" "$NGINX_DIR/nginx.conf"
        sed -i "s/server [0-9.]*:8083;/server $HOST_IP:8083;/" "$NGINX_DIR/nginx.conf"
    fi
    echo "nginx.conf updated"
fi

echo "✓ Nginx configured to use host IP: $HOST_IP"
