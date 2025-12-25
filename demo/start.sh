#!/bin/bash

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}🚀 Starting WebFlux SSE Application${NC}"
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

    # Start authorization-server on port 8082
    echo -e "${YELLOW}⏳ Starting authorization-server on port 8082...${NC}"
    mvn -f "$SCRIPT_DIR/../pom.xml" -pl backend/authorization/authorization-server spring-boot:run > /dev/null 2>&1 &
    AUTH_PID=$!

    # Start search-server on port 8081
    echo -e "${YELLOW}⏳ Starting search-server on port 8081...${NC}"
    mvn -f "$SCRIPT_DIR/../pom.xml" -pl backend/search/search-server spring-boot:run > /dev/null 2>&1 &
    SEARCH_PID=$!

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
        echo -e "${YELLOW}⚠️ Authorization-server may still be starting. Check http://localhost:8082${NC}"
    fi

    # Wait for search-server to start
    echo -e "${YELLOW}⏳ Waiting for search-server to start...${NC}"
    timeout=120
    while [ $timeout -gt 0 ]; do
        if curl -s http://localhost:8081/api/v1/events > /dev/null 2>&1; then
            echo -e "${GREEN}✅ Search-server is running on port 8081!${NC}"
            break
        fi
        echo -n "."
        sleep 3
        timeout=$((timeout-3))
    done

    if [ $timeout -le 0 ]; then
        echo -e "${YELLOW}⚠️ Search-server may still be starting. Check http://localhost:8081${NC}"
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
    echo -e "   Search Service: ${YELLOW}http://localhost:8081${NC}"
    echo -e "   Authorization Service: ${YELLOW}http://localhost:8082${NC}"
    echo
    echo -e "${BLUE}📚 API Documentation:${NC}"
    echo -e "   Search Service API: ${YELLOW}http://localhost:8081/swagger-ui.html${NC}"
    echo -e "   Authorization Service API: ${YELLOW}http://localhost:8082/swagger-ui.html${NC}"
    echo
    echo -e "${BLUE}🗃️ Database Connection:${NC}"
    echo -e "   🔗 Host: ${YELLOW}localhost:5432${NC}"
    echo -e "   📂 Database: ${YELLOW}eventdb${NC}"
    echo -e "   👤 User: ${YELLOW}postgres${NC}"
    echo
    echo -e "${BLUE}📋 Available Commands:${NC}"
    echo -e "   🛑 Stop all services: ${YELLOW}./stop.sh${NC}"
    echo -e "   🛑 Stop containers: ${YELLOW}docker-compose down${NC}"
    echo -e "   📄 View logs: ${YELLOW}docker-compose logs [postgres|elasticsearch|nginx]${NC}"
    echo -e "   🔍 Connect to database: ${YELLOW}docker-compose exec postgres psql -U postgres -d eventdb${NC}"
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
    pkill -f "authorization-server.*spring-boot:run" > /dev/null 2>&1
    pkill -f "search-server.*spring-boot:run" > /dev/null 2>&1
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

# Keep the script running
if [ ! -z "$AUTH_PID" ] || [ ! -z "$SEARCH_PID" ]; then
    wait
fi