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

# Stop JVisualVM/JConsole first
if [ -f "$SCRIPT_DIR/.pids/jvisualvm.pid" ]; then
    echo -e "${YELLOW}📊 Stopping JVisualVM...${NC}"
    JVISUALVM_PID=$(cat "$SCRIPT_DIR/.pids/jvisualvm.pid")
    kill $JVISUALVM_PID > /dev/null 2>&1
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✅ JVisualVM stopped${NC}"
    else
        echo -e "${YELLOW}ℹ️  JVisualVM was not running${NC}"
    fi
elif [ -f "$SCRIPT_DIR/.pids/jconsole.pid" ]; then
    echo -e "${YELLOW}📊 Stopping JConsole...${NC}"
    JCONSOLE_PID=$(cat "$SCRIPT_DIR/.pids/jconsole.pid")
    kill $JCONSOLE_PID > /dev/null 2>&1
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✅ JConsole stopped${NC}"
    else
        echo -e "${YELLOW}ℹ️  JConsole was not running${NC}"
    fi
else
    echo -e "${YELLOW}ℹ️  No monitoring tools were started${NC}"
fi

# Stop authorization-server
echo -e "${YELLOW}🔧 Stopping authorization-server...${NC}"
pkill -f "authorization-server.*spring-boot:run" > /dev/null 2>&1
pkill -f "AuthorizationServiceApplication" > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ Authorization-server stopped${NC}"
else
    echo -e "${YELLOW}ℹ️  No running authorization-server found${NC}"
fi

# Stop search-server-wf (reactive)
echo -e "${YELLOW}🔧 Stopping search-server-wf (WebFlux)...${NC}"
pkill -f "search-server-wf.*spring-boot:run" > /dev/null 2>&1
# Also stop Java processes for search-server-wf
pkill -f "search-server-wf/.*SearchServiceWfApplication" > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ search-server-wf (WebFlux) stopped${NC}"
else
    echo -e "${YELLOW}ℹ️  No running search-server-wf (WebFlux) found${NC}"
fi

# Stop search-server-vt
echo -e "${YELLOW}🔧 Stopping search-server-vt (Virtual Threads)...${NC}"
pkill -f "search-server-vt.*spring-boot:run" > /dev/null 2>&1
pkill -f "search-server-vt/.*SearchServiceVtApplication" > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo -e "${GREEN}✅ search-server-vt (Virtual Threads) stopped${NC}"
else
    echo -e "${YELLOW}ℹ️  No running search-server-vt found${NC}"
fi

# Stop Docker containers
echo -e "${YELLOW}📦 Stopping PostgreSQL database...${NC}"
if docker-compose -f "$SCRIPT_DIR/docker-compose.yml" down; then
    echo -e "${GREEN}✅ Database container stopped${NC}"
else
    echo -e "${RED}❌ Failed to stop database container${NC}"
fi

# Clean up PID files
if [ -d "$SCRIPT_DIR/.pids" ]; then
    rm -rf "$SCRIPT_DIR/.pids"
fi

echo
echo -e "${GREEN}🎉 All services stopped successfully!${NC}"