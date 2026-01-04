#!/bin/bash

# Script to monitor Spring Boot application logs
# Usage: ./monitor-logs.sh [search|auth|both]

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

MODE=${1:-both}

case $MODE in
    search)
        echo -e "${BLUE}Monitoring search-server logs...${NC}"
        echo -e "${YELLOW}Process info:${NC}"
        ps aux | grep "search-server.*spring-boot:run" | grep -v grep | head -1
        echo ""
        jps -v | grep SearchServiceApplication
        ;;
    auth)
        echo -e "${BLUE}Monitoring authorization-server logs...${NC}"
        echo -e "${YELLOW}Process info:${NC}"
        ps aux | grep "authorization-server.*spring-boot:run" | grep -v grep | head -1
        echo ""
        jps -v | grep AuthorizationServiceApplication
        ;;
    both)
        echo -e "${BLUE}=== Service Status ===${NC}"
        echo ""
        echo -e "${CYAN}Search Server (port 8081):${NC}"
        SEARCH_PID=$(jps -l | grep SearchServiceApplication | awk '{print $1}')
        if [ -n "$SEARCH_PID" ]; then
            echo -e "${GREEN}✓ Running (PID: $SEARCH_PID)${NC}"
            ps -p $SEARCH_PID -o pid,vsz,rss,%mem,command | tail -1 | awk '{printf "  Memory: RSS=%sMB, VSZ=%sMB, MEM=%s%%\n", $3/1024, $2/1024, $4}'
            # Show JVM heap settings
            jps -v | grep SearchServiceApplication | grep -o "\-Xmx[^ ]*" | sed 's/-Xmx/  Heap Max: /' || echo "  Heap Max: Not specified"
        else
            echo -e "${RED}✗ Not running${NC}"
        fi
        echo ""

        echo -e "${CYAN}Authorization Server (port 8082):${NC}"
        AUTH_PID=$(jps -l | grep AuthorizationServiceApplication | awk '{print $1}')
        if [ -n "$AUTH_PID" ]; then
            echo -e "${GREEN}✓ Running (PID: $AUTH_PID)${NC}"
            ps -p $AUTH_PID -o pid,vsz,rss,%mem,command | tail -1 | awk '{printf "  Memory: RSS=%sMB, VSZ=%sMB, MEM=%s%%\n", $3/1024, $2/1024, $4}'
            # Show JVM heap settings
            jps -v | grep AuthorizationServiceApplication | grep -o "\-Xmx[^ ]*" | sed 's/-Xmx/  Heap Max: /' || echo "  Heap Max: Not specified"
        else
            echo -e "${RED}✗ Not running${NC}"
        fi
        echo ""

        echo -e "${BLUE}=== Recent API Activity ===${NC}"
        echo -e "${CYAN}Testing bulk endpoint...${NC}"
        RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost/api/v1/events/bulk \
            -H "Content-Type: application/json" \
            -d '{"events":[{"title":"Health Check","description":"test"}]}')
        HTTP_CODE=$(echo "$RESPONSE" | tail -1)
        BODY=$(echo "$RESPONSE" | head -n -1)

        if [ "$HTTP_CODE" = "201" ]; then
            echo -e "${GREEN}✓ Bulk endpoint responding (HTTP $HTTP_CODE)${NC}"
            echo "$BODY" | jq -r '.[0] | "  Created event ID: \(.id)"' 2>/dev/null || echo "$BODY"
        else
            echo -e "${RED}✗ Bulk endpoint error (HTTP $HTTP_CODE)${NC}"
            echo "$BODY"
        fi
        echo ""

        echo -e "${BLUE}=== Database Connections ===${NC}"
        docker-compose -f "$(dirname "$0")/docker-compose.yml" exec -T postgres \
            psql -U postgres -d eventdb -c "SELECT count(*) as active_connections FROM pg_stat_activity WHERE datname='eventdb';" 2>/dev/null | grep -A 1 active || echo "Unable to check"
        ;;
    *)
        echo -e "${RED}Invalid mode. Use: search, auth, or both${NC}"
        exit 1
        ;;
esac
