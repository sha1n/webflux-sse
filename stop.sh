#!/bin/bash

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}🛑 Stopping WebFlux SSE Services${NC}"
echo

# Stop authorization-service
echo -e "${YELLOW}🔧 Stopping authorization-service...${NC}"
pkill -f "authorization-service.*spring-boot:run" > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Authorization-service stopped${NC}"
else
    echo -e "${YELLOW}ℹ️  No running authorization-service found${NC}"
fi

# Stop search-service
echo -e "${YELLOW}🔧 Stopping search-service...${NC}"
pkill -f "search-service.*spring-boot:run" > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Search-service stopped${NC}"
else
    echo -e "${YELLOW}ℹ️  No running search-service found${NC}"
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