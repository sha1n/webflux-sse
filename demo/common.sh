#!/bin/bash

# Common functions shared across start scripts

# Get the directory where the calling script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[1]}")" && pwd)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to check if Docker is running
check_docker() {
    if ! docker info > /dev/null 2>&1; then
        echo -e "${RED}❌ Docker is not running. Please start Docker and try again.${NC}"
        exit 1
    fi
    echo -e "${GREEN}✅ Docker is running${NC}"
}

# Function to start database
start_database() {
    echo -e "${BLUE}📦 Starting Docker containers (PostgreSQL, Elasticsearch, Nginx)...${NC}"

    # Stop existing containers if they exist
    docker-compose -f "$SCRIPT_DIR/docker-compose.yml" down > /dev/null 2>&1

    # Start the database
    if docker-compose -f "$SCRIPT_DIR/docker-compose.yml" up -d; then
        echo -e "${GREEN}✅ Docker containers started${NC}"

        # Wait for database to be ready
        echo -e "${YELLOW}⏳ Waiting for database to be ready...${NC}"

        # Wait up to 60 seconds for database to be ready
        timeout=60
        while [ $timeout -gt 0 ]; do
            if docker-compose -f "$SCRIPT_DIR/docker-compose.yml" exec -T postgres pg_isready -U postgres -d eventdb > /dev/null 2>&1; then
                echo -e "${GREEN}✅ Database is ready!${NC}"
                break
            fi
            echo -n "."
            sleep 2
            timeout=$((timeout-2))
        done

        if [ $timeout -le 0 ]; then
            echo -e "${RED}❌ Database failed to start within 60 seconds${NC}"
            docker-compose -f "$SCRIPT_DIR/docker-compose.yml" logs postgres
            exit 1
        fi
    else
        echo -e "${RED}❌ Failed to start database${NC}"
        exit 1
    fi
}

# Function to start JVisualVM or JConsole
start_jvisualvm() {
    echo -e "${BLUE}📊 Starting JVM monitoring...${NC}"

    # Create directory for storing PIDs
    mkdir -p "$SCRIPT_DIR/.pids"

    # Store service PIDs for cleanup
    echo $AUTH_PID > "$SCRIPT_DIR/.pids/auth.pid"
    echo $SEARCH_PID > "$SCRIPT_DIR/.pids/search.pid"
    echo $SEARCH_VIRTUAL_PID > "$SCRIPT_DIR/.pids/search_virtual.pid"

    # Wait a moment for JVMs to fully initialize
    sleep 5

    # Try JVisualVM first
    if command -v jvisualvm &> /dev/null; then
        # Launch JVisualVM (it will auto-discover all local Java processes)
        jvisualvm > /dev/null 2>&1 &
        JVISUALVM_PID=$!
        echo $JVISUALVM_PID > "$SCRIPT_DIR/.pids/jvisualvm.pid"
        echo -e "${GREEN}✅ JVisualVM launched (PID: $JVISUALVM_PID)${NC}"
        echo -e "${BLUE}   All Java processes will be visible in JVisualVM${NC}"
        echo -e "${BLUE}   JMX Ports: auth(9010), search-wf(9011), search-vt(9012)${NC}"
    # Fall back to JConsole
    elif command -v jconsole &> /dev/null; then
        echo -e "${YELLOW}⚠️  JVisualVM not found, using JConsole instead${NC}"

        # Launch JConsole without PID argument
        # This opens a connection dialog showing all local Java processes
        jconsole > /dev/null 2>&1 &
        JCONSOLE_PID=$!
        echo $JCONSOLE_PID > "$SCRIPT_DIR/.pids/jconsole.pid"
        echo -e "${GREEN}✅ JConsole launched (PID: $JCONSOLE_PID)${NC}"
        echo
        echo -e "${BLUE}   To connect to services:${NC}"
        echo -e "${BLUE}   1. Select 'Remote Process' tab${NC}"
        echo -e "${BLUE}   2. Enter one of these JMX URLs:${NC}"
        echo -e "${YELLOW}      • Authorization Server: localhost:9010${NC}"
        echo -e "${YELLOW}      • Search Server (WebFlux): localhost:9011${NC}"
        echo -e "${YELLOW}      • Search Server (Virtual Threads): localhost:9012${NC}"
        echo -e "${BLUE}   3. Click Connect${NC}"
        echo -e "${BLUE}   4. Use File → New Connection to add more services${NC}"
        echo
    else
        echo -e "${YELLOW}⚠️  Neither JVisualVM nor JConsole found. Install JDK tools for JVM monitoring.${NC}"
    fi
}

# Function to cleanup on exit
cleanup() {
    echo
    echo -e "${YELLOW}🧹 Cleaning up...${NC}"

    # Kill JVisualVM if it was started
    if [ -f "$SCRIPT_DIR/.pids/jvisualvm.pid" ]; then
        JVISUALVM_PID=$(cat "$SCRIPT_DIR/.pids/jvisualvm.pid")
        if [ ! -z "$JVISUALVM_PID" ]; then
            kill $JVISUALVM_PID > /dev/null 2>&1
            echo -e "${GREEN}✅ JVisualVM stopped${NC}"
        fi
    fi

    # Kill JConsole if it was started
    if [ -f "$SCRIPT_DIR/.pids/jconsole.pid" ]; then
        JCONSOLE_PID=$(cat "$SCRIPT_DIR/.pids/jconsole.pid")
        if [ ! -z "$JCONSOLE_PID" ]; then
            kill $JCONSOLE_PID > /dev/null 2>&1
            echo -e "${GREEN}✅ JConsole stopped${NC}"
        fi
    fi

    # Kill service processes
    if [ ! -z "$AUTH_PID" ]; then
        kill $AUTH_PID > /dev/null 2>&1
    fi
    if [ ! -z "$SEARCH_PID" ]; then
        kill $SEARCH_PID > /dev/null 2>&1
    fi
    if [ ! -z "$SEARCH_VIRTUAL_PID" ]; then
        kill $SEARCH_VIRTUAL_PID > /dev/null 2>&1
    fi
    pkill -f "authorization-server.*spring-boot:run" > /dev/null 2>&1
    pkill -f "search-server-wf.*spring-boot:run" > /dev/null 2>&1
    pkill -f "search-server-vt.*spring-boot:run" > /dev/null 2>&1
    docker-compose -f "$SCRIPT_DIR/docker-compose.yml" down > /dev/null 2>&1

    # Clean up PID files
    rm -rf "$SCRIPT_DIR/.pids"

    echo -e "${GREEN}✅ Cleanup complete${NC}"
    exit 0
}

# Function to wait for a service to start
# Args: $1 = service name, $2 = port, $3 = health check URL, $4 = optional log file path
wait_for_service() {
    local service_name=$1
    local port=$2
    local health_url=$3
    local log_file=$4

    echo -e "${YELLOW}⏳ Waiting for $service_name to start...${NC}"
    timeout=120
    while [ $timeout -gt 0 ]; do
        if curl -s "$health_url" > /dev/null 2>&1; then
            echo -e "${GREEN}✅ $service_name is running on port $port!${NC}"
            return 0
        fi
        echo -n "."
        sleep 3
        timeout=$((timeout-3))
    done

    if [ ! -z "$log_file" ]; then
        echo -e "${YELLOW}⚠️ $service_name may still be starting. Check logs: tail -f $log_file${NC}"
    else
        echo -e "${YELLOW}⚠️ $service_name may still be starting. Check http://localhost:$port${NC}"
    fi
    return 1
}
