#!/bin/bash

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}🛑 Stopping WebFlux SSE Application${NC}"
echo

# Stop Spring Boot application (if running via Maven)
echo -e "${YELLOW}🔧 Stopping Spring Boot application...${NC}"
pkill -f "spring-boot:run" > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Spring Boot application stopped${NC}"
else
    echo -e "${YELLOW}ℹ️  No running Spring Boot application found${NC}"
fi

# Stop Docker containers
echo -e "${YELLOW}📦 Stopping PostgreSQL database...${NC}"
if docker-compose down; then
    echo -e "${GREEN}✅ Database container stopped${NC}"
else
    echo -e "${RED}❌ Failed to stop database container${NC}"
fi

echo
echo -e "${GREEN}🎉 All services stopped successfully!${NC}"