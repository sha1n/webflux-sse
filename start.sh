#!/bin/bash

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
    echo -e "${BLUE}📦 Starting PostgreSQL database...${NC}"
    
    # Stop existing containers if they exist
    docker-compose down > /dev/null 2>&1
    
    # Start the database
    if docker-compose up -d; then
        echo -e "${GREEN}✅ Database container started${NC}"
        
        # Wait for database to be ready
        echo -e "${YELLOW}⏳ Waiting for database to be ready...${NC}"
        
        # Wait up to 60 seconds for database to be ready
        timeout=60
        while [ $timeout -gt 0 ]; do
            if docker-compose exec -T postgres pg_isready -U postgres -d eventdb > /dev/null 2>&1; then
                echo -e "${GREEN}✅ Database is ready!${NC}"
                break
            fi
            echo -n "."
            sleep 2
            timeout=$((timeout-2))
        done
        
        if [ $timeout -le 0 ]; then
            echo -e "${RED}❌ Database failed to start within 60 seconds${NC}"
            docker-compose logs postgres
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

    # Start authorization-service on port 8082
    echo -e "${YELLOW}⏳ Starting authorization-service on port 8082...${NC}"
    mvn -pl authorization-service spring-boot:run > /dev/null 2>&1 &
    AUTH_PID=$!

    # Start search-service on port 8081
    echo -e "${YELLOW}⏳ Starting search-service on port 8081...${NC}"
    mvn -pl search-service spring-boot:run > /dev/null 2>&1 &
    SEARCH_PID=$!

    # Wait for authorization-service to start
    echo -e "${YELLOW}⏳ Waiting for authorization-service to start...${NC}"
    timeout=120
    while [ $timeout -gt 0 ]; do
        if curl -s http://localhost:8082/api/permissions > /dev/null 2>&1; then
            echo -e "${GREEN}✅ Authorization-service is running on port 8082!${NC}"
            break
        fi
        echo -n "."
        sleep 3
        timeout=$((timeout-3))
    done

    if [ $timeout -le 0 ]; then
        echo -e "${YELLOW}⚠️ Authorization-service may still be starting. Check http://localhost:8082${NC}"
    fi

    # Wait for search-service to start
    echo -e "${YELLOW}⏳ Waiting for search-service to start...${NC}"
    timeout=120
    while [ $timeout -gt 0 ]; do
        if curl -s http://localhost:8081/api/events > /dev/null 2>&1; then
            echo -e "${GREEN}✅ Search-service is running on port 8081!${NC}"
            break
        fi
        echo -n "."
        sleep 3
        timeout=$((timeout-3))
    done

    if [ $timeout -le 0 ]; then
        echo -e "${YELLOW}⚠️ Search-service may still be starting. Check http://localhost:8081${NC}"
    fi
}

# Function to display final information
show_info() {
    echo
    echo -e "${GREEN}🎉 Setup complete!${NC}"
    echo
    echo -e "${BLUE}📱 Application URLs:${NC}"
    echo -e "   🌐 Search Service (Events + Search): ${YELLOW}http://localhost:8081${NC}"
    echo -e "   📡 SSE Stream: ${YELLOW}http://localhost:8081/api/events/stream${NC}"
    echo -e "   📊 Events API: ${YELLOW}http://localhost:8081/api/events${NC}"
    echo -e "   🔍 Search API: ${YELLOW}http://localhost:8081/api/search${NC}"
    echo -e "   🔐 Authorization Service: ${YELLOW}http://localhost:8082${NC}"
    echo -e "   🔑 Permissions API: ${YELLOW}http://localhost:8082/api/permissions${NC}"
    echo
    echo -e "${BLUE}🗃️ Database Connection:${NC}"
    echo -e "   🔗 Host: ${YELLOW}localhost:5432${NC}"
    echo -e "   📂 Database: ${YELLOW}eventdb${NC}"
    echo -e "   👤 User: ${YELLOW}postgres${NC}"
    echo
    echo -e "${BLUE}📋 Available Commands:${NC}"
    echo -e "   🛑 Stop all services: ${YELLOW}./stop.sh${NC}"
    echo -e "   🛑 Stop database: ${YELLOW}docker-compose down${NC}"
    echo -e "   📄 View database logs: ${YELLOW}docker-compose logs postgres${NC}"
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
    pkill -f "authorization-service.*spring-boot:run" > /dev/null 2>&1
    pkill -f "search-service.*spring-boot:run" > /dev/null 2>&1
    docker-compose down > /dev/null 2>&1
    echo -e "${GREEN}✅ Cleanup complete${NC}"
    exit 0
}

# Set trap to cleanup on script exit
trap cleanup INT TERM

# Main execution
check_docker
start_database
start_applications
show_info

# Keep the script running
if [ ! -z "$AUTH_PID" ] || [ ! -z "$SEARCH_PID" ]; then
    wait
fi