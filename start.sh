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

# Function to start application
start_application() {
    echo -e "${BLUE}🔧 Starting Spring Boot application...${NC}"
    
    # Check if Maven is installed
    if ! command -v mvn &> /dev/null; then
        echo -e "${RED}❌ Maven is not installed. Please install Maven and try again.${NC}"
        exit 1
    fi
    
    # Start the application
    echo -e "${YELLOW}⏳ Building and starting application (this may take a moment)...${NC}"
    mvn spring-boot:run &
    APP_PID=$!
    
    # Wait for application to start
    echo -e "${YELLOW}⏳ Waiting for application to start...${NC}"
    timeout=120
    while [ $timeout -gt 0 ]; do
        if curl -s http://localhost:8080/actuator/health > /dev/null 2>&1 || curl -s http://localhost:8080 > /dev/null 2>&1; then
            echo -e "${GREEN}✅ Application is running!${NC}"
            break
        fi
        echo -n "."
        sleep 3
        timeout=$((timeout-3))
    done
    
    if [ $timeout -le 0 ]; then
        echo -e "${YELLOW}⚠️ Application may still be starting. Check http://localhost:8080${NC}"
    fi
}

# Function to display final information
show_info() {
    echo
    echo -e "${GREEN}🎉 Setup complete!${NC}"
    echo
    echo -e "${BLUE}📱 Application URLs:${NC}"
    echo -e "   🌐 Web Interface: ${YELLOW}http://localhost:8080${NC}"
    echo -e "   📡 SSE Stream: ${YELLOW}http://localhost:8080/api/events/stream${NC}"
    echo -e "   📊 REST API: ${YELLOW}http://localhost:8080/api/events${NC}"
    echo
    echo -e "${BLUE}🗃️ Database Connection:${NC}"
    echo -e "   🔗 Host: ${YELLOW}localhost:5432${NC}"
    echo -e "   📂 Database: ${YELLOW}eventdb${NC}"
    echo -e "   👤 User: ${YELLOW}postgres${NC}"
    echo
    echo -e "${BLUE}📋 Available Commands:${NC}"
    echo -e "   🛑 Stop database: ${YELLOW}docker-compose down${NC}"
    echo -e "   📄 View database logs: ${YELLOW}docker-compose logs postgres${NC}"
    echo -e "   🔍 Connect to database: ${YELLOW}docker-compose exec postgres psql -U postgres -d eventdb${NC}"
    echo
    echo -e "${GREEN}Press Ctrl+C to stop the application${NC}"
}

# Function to cleanup on exit
cleanup() {
    echo
    echo -e "${YELLOW}🧹 Cleaning up...${NC}"
    if [ ! -z "$APP_PID" ]; then
        kill $APP_PID > /dev/null 2>&1
    fi
    docker-compose down > /dev/null 2>&1
    echo -e "${GREEN}✅ Cleanup complete${NC}"
    exit 0
}

# Set trap to cleanup on script exit
trap cleanup INT TERM

# Main execution
check_docker
start_database
start_application
show_info

# Keep the script running
if [ ! -z "$APP_PID" ]; then
    wait $APP_PID
fi