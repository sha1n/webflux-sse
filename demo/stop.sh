#!/bin/bash

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}🛑 Stopping WebFlux SSE Services${NC}"
echo

# Stop authorization-server
echo -e "${YELLOW}🔧 Stopping authorization-server...${NC}"
pkill -f "authorization-server.*spring-boot:run" > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Authorization-server stopped${NC}"
else
    echo -e "${YELLOW}ℹ️  No running authorization-server found${NC}"
fi

# Stop search-server
echo -e "${YELLOW}🔧 Stopping search-server...${NC}"
pkill -f "search-server.*spring-boot:run" > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Search-server stopped${NC}"
else
    echo -e "${YELLOW}ℹ️  No running search-server found${NC}"
fi

# Stop Docker containers
echo -e "${YELLOW}📦 Stopping PostgreSQL database...${NC}"
if docker-compose -f "$SCRIPT_DIR/docker-compose.yml" down; then
    echo -e "${GREEN}✅ Database container stopped${NC}"
else
    echo -e "${RED}❌ Failed to stop database container${NC}"
fi

echo
echo -e "${GREEN}🎉 All services stopped successfully!${NC}"