#!/bin/bash

# Enhanced start script with logging
# This version captures logs to files for debugging

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$SCRIPT_DIR/logs"

# Create logs directory
mkdir -p "$LOG_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}🚀 Starting WebFlux SSE Application with Logging${NC}"
echo -e "${YELLOW}Logs will be saved to: $LOG_DIR${NC}"
echo

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

# Function to start applications
start_applications() {
    echo -e "${BLUE}🔧 Starting Spring Boot applications...${NC}"

    # Check if Maven is installed
    if ! command -v mvn &> /dev/null; then
        echo -e "${RED}❌ Maven is not installed. Please install Maven and try again.${NC}"
        exit 1
    fi

    # Start authorization-server on port 8082 with 1GB RAM
    echo -e "${YELLOW}⏳ Starting authorization-server on port 8082 (1GB RAM)...${NC}"
    echo -e "${BLUE}   Log file: $LOG_DIR/authorization-server.log${NC}"
    export JAVA_TOOL_OPTIONS="-Xms1024m -Xmx1024m"
    mvn -f "$SCRIPT_DIR/../pom.xml" -pl backend/authorization/authorization-server spring-boot:run \
        > "$LOG_DIR/authorization-server.log" 2>&1 &
    AUTH_PID=$!
    unset JAVA_TOOL_OPTIONS

    # Start search-server-wf (reactive) on port 8081 with 1GB RAM
    echo -e "${YELLOW}⏳ Starting search-server-wf (WebFlux) on port 8081 (1GB RAM)...${NC}"
    echo -e "${BLUE}   Log file: $LOG_DIR/search-server-wf.log${NC}"
    export JAVA_TOOL_OPTIONS="-Xms1024m -Xmx1024m"
    mvn -f "$SCRIPT_DIR/../pom.xml" -pl backend/search/search-server-wf spring-boot:run \
        > "$LOG_DIR/search-server-wf.log" 2>&1 &
    SEARCH_PID=$!
    unset JAVA_TOOL_OPTIONS

    # Start search-server-vt on port 8083 with 1GB RAM
    echo -e "${YELLOW}⏳ Starting search-server-vt (Virtual Threads) on port 8083 (1GB RAM)...${NC}"
    echo -e "${BLUE}   Log file: $LOG_DIR/search-server-vt.log${NC}"
    export JAVA_TOOL_OPTIONS="-Xms1024m -Xmx1024m"
    mvn -f "$SCRIPT_DIR/../pom.xml" -pl backend/search/search-server-vt spring-boot:run \
        > "$LOG_DIR/search-server-vt.log" 2>&1 &
    SEARCH_VIRTUAL_PID=$!
    unset JAVA_TOOL_OPTIONS

    # Wait for authorization-server to start
    echo -e "${YELLOW}⏳ Waiting for authorization-server to start...${NC}"
    timeout=120
    while [ $timeout -gt 0 ]; do
        if curl -s http://localhost:8082/api/v1/permissions > /dev/null 2>&1; then
            echo -e "${GREEN}✅ Authorization-server is running on port 8082!${NC}"
            break
        fi
        echo -n "."
        sleep 3
        timeout=$((timeout-3))
    done

    if [ $timeout -le 0 ]; then
        echo -e "${YELLOW}⚠️ Authorization-server may still be starting. Check logs: tail -f $LOG_DIR/authorization-server.log${NC}"
    fi

    # Wait for search-server-wf to start
    echo -e "${YELLOW}⏳ Waiting for search-server-wf (WebFlux) to start...${NC}"
    timeout=120
    while [ $timeout -gt 0 ]; do
        if curl -s http://localhost:8081/api/v1/events > /dev/null 2>&1; then
            echo -e "${GREEN}✅ search-server-wf (WebFlux) is running on port 8081!${NC}"
            break
        fi
        echo -n "."
        sleep 3
        timeout=$((timeout-3))
    done

    if [ $timeout -le 0 ]; then
        echo -e "${YELLOW}⚠️ search-server-wf may still be starting. Check logs: tail -f $LOG_DIR/search-server-wf.log${NC}"
    fi

    # Wait for search-server-vt to start
    echo -e "${YELLOW}⏳ Waiting for search-server-vt (Virtual Threads) to start...${NC}"
    timeout=120
    while [ $timeout -gt 0 ]; do
        if curl -s http://localhost:8083/api/v1/events > /dev/null 2>&1; then
            echo -e "${GREEN}✅ search-server-vt (Virtual Threads) is running on port 8083!${NC}"
            break
        fi
        echo -n "."
        sleep 3
        timeout=$((timeout-3))
    done

    if [ $timeout -le 0 ]; then
        echo -e "${YELLOW}⚠️ search-server-vt may still be starting. Check logs: tail -f $LOG_DIR/search-server-vt.log${NC}"
    fi
}

# Function to display final information
show_info() {
    echo
    echo -e "${GREEN}🎉 Setup complete!${NC}"
    echo
    echo -e "${BLUE}📱 Application URLs:${NC}"
    echo -e "   🌐 Main UI (Nginx Gateway): ${YELLOW}http://localhost${NC}"
    echo -e "   📡 SSE Stream: ${YELLOW}http://localhost/api/v1/events${NC}"
    echo -e "   🔍 Search: ${YELLOW}http://localhost/search.html${NC}"
    echo -e "   🔐 Permissions: ${YELLOW}http://localhost/permissions.html${NC}"
    echo
    echo -e "${BLUE}🔧 Backend Services (Direct Access):${NC}"
    echo -e "   Search Service (WebFlux): ${YELLOW}http://localhost:8081${NC}"
    echo -e "   Authorization Service: ${YELLOW}http://localhost:8082${NC}"
    echo -e "   Search Service (Virtual Threads): ${YELLOW}http://localhost:8083${NC}"
    echo
    echo -e "${BLUE}📋 Log Files:${NC}"
    echo -e "   Search Service (WebFlux): ${YELLOW}tail -f $LOG_DIR/search-server-wf.log${NC}"
    echo -e "   Authorization Service: ${YELLOW}tail -f $LOG_DIR/authorization-server.log${NC}"
    echo -e "   Search Service (VT): ${YELLOW}tail -f $LOG_DIR/search-server-vt.log${NC}"
    echo -e "   Monitor script: ${YELLOW}./monitor-logs.sh${NC}"
    echo
    echo -e "${BLUE}📋 Available Commands:${NC}"
    echo -e "   🛑 Stop all services: ${YELLOW}./stop.sh${NC}"
    echo -e "   📄 View logs: ${YELLOW}tail -f $LOG_DIR/*.log${NC}"
    echo
    echo -e "${GREEN}Press Ctrl+C to stop all applications${NC}"
}

# Function to cleanup on exit
cleanup() {
    echo
    echo -e "${YELLOW}🧹 Cleaning up...${NC}"
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
    echo -e "${GREEN}✅ Cleanup complete${NC}"
    exit 0
}

# Set trap to cleanup on script exit
trap cleanup INT TERM

# Main execution
check_docker
"$SCRIPT_DIR/../frontend/configure-nginx-host.sh"
start_database
start_applications
show_info

# Keep the script running - wait for services
# This will exit when all services are stopped (e.g., by stop.sh or Ctrl+C)
# Check for both Maven processes (spring-boot:run) and Java processes (Application classes)
while pgrep -f "authorization-server.*spring-boot:run" > /dev/null || \
      pgrep -f "search-server-wf.*spring-boot:run" > /dev/null || \
      pgrep -f "search-server-vt.*spring-boot:run" > /dev/null || \
      pgrep -f "AuthorizationServiceApplication" > /dev/null || \
      pgrep -f "SearchServiceWfApplication\|SearchServiceVtApplication" > /dev/null; do
    sleep 2
done

echo
echo -e "${YELLOW}⚠️  All services have stopped${NC}"
exit 0
