#!/bin/bash

# k6-based search load testing script using Docker
# Usage: ./k6-search-load-test.sh --vus <number> [--duration <time>] [--limit <number>] [--user <user_id>]

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
VUS=""
DURATION="60s"
LIMIT=200
SPECIFIC_USER=""
TIMEOUT="120s"
STACK=""
# Use NGINX container name when running on same Docker network
# This allows k6 container to reach NGINX container directly
BASE_URL="http://nginx"
DOCKER_NETWORK="demo_default"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TERMS_FILE="$SCRIPT_DIR/search-terms.txt"
K6_SCRIPT="$SCRIPT_DIR/search-load-test.js"

# Function to display usage
usage() {
    echo -e "${BLUE}Usage: $0 --vus <number> --stack <wf|vt> [--duration <time>] [--limit <number>] [--user <user_id>] [--timeout <time>]${NC}"
    echo -e "  ${GREEN}--vus${NC}        Number of virtual users (concurrent clients) - required"
    echo -e "               Each VU runs continuous requests for the duration"
    echo -e "  ${GREEN}--stack${NC}      Backend stack to test: 'wf' (WebFlux) or 'vt' (Virtual Threads) - required"
    echo -e "  ${GREEN}--duration${NC}   Test duration (optional, default: 60s)"
    echo -e "               Examples: 30s, 5m, 1h"
    echo -e "  ${GREEN}--limit${NC}      Maximum number of results per search (optional, default: 200)"
    echo -e "  ${GREEN}--timeout${NC}    HTTP request timeout (optional, default: 120s)"
    echo -e "               Examples: 30s, 2m, 90s"
    echo -e "  ${GREEN}--user${NC}       Specific user ID to search as (optional)"
    echo -e "               Valid users: admin, user1, user2, user3"
    echo -e "               If not specified, VUs will randomly rotate through all users"
    echo ""
    echo "Examples:"
    echo "  $0 --vus 10 --stack wf                           # 10 users against WebFlux for 60s"
    echo "  $0 --vus 50 --stack vt --duration 5m             # 50 users against VT for 5 minutes"
    echo "  $0 --vus 20 --stack wf --duration 2m --limit 500 # 20 users, 2 min, 500 result limit"
    echo "  $0 --vus 100 --stack vt --timeout 120s           # 100 users, 120s timeout"
    echo "  $0 --vus 100 --stack wf --user admin             # 100 users all as admin"
    echo ""
    echo "Note: k6 runs on the same Docker network as your services (demo_default)"
    echo "      and sends requests through the NGINX container"
    echo ""
    echo "k6 will provide real-time metrics including:"
    echo "  - Requests per second (RPS)"
    echo "  - Response time percentiles (p50, p90, p95, p99)"
    echo "  - Error rates"
    echo "  - Number of results returned per query"
    exit 1
}

# Parse named arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --vus)
            VUS="$2"
            shift 2
            ;;
        --stack)
            STACK="$2"
            shift 2
            ;;
        --duration)
            DURATION="$2"
            shift 2
            ;;
        --limit)
            LIMIT="$2"
            shift 2
            ;;
        --timeout)
            TIMEOUT="$2"
            shift 2
            ;;
        --user)
            SPECIFIC_USER="$2"
            shift 2
            ;;
        -h|--help)
            usage
            ;;
        *)
            echo -e "${RED}Error: Unknown argument '$1'${NC}"
            usage
            ;;
    esac
done

# Validate required arguments
if [ -z "$VUS" ]; then
    echo -e "${RED}Error: --vus argument is required${NC}"
    usage
fi

if [ -z "$STACK" ]; then
    echo -e "${RED}Error: --stack argument is required${NC}"
    usage
fi

# Validate stack parameter
if [[ "$STACK" != "wf" && "$STACK" != "vt" ]]; then
    echo -e "${RED}Error: --stack must be 'wf' (WebFlux) or 'vt' (Virtual Threads)${NC}"
    exit 1
fi

# Set API path based on stack
if [[ "$STACK" == "wf" ]]; then
    API_PATH="/api-reactive/rpc/v1/search"
    STACK_NAME="WebFlux"
else
    API_PATH="/api-virtual/rpc/v1/search"
    STACK_NAME="Virtual Threads"
fi

# Validate VUS is a positive number
if ! [[ "$VUS" =~ ^[0-9]+$ ]] || [ "$VUS" -le 0 ]; then
    echo -e "${RED}Error: --vus must be a valid positive integer${NC}"
    exit 1
fi

# Validate limit
if ! [[ "$LIMIT" =~ ^[0-9]+$ ]] || [ "$LIMIT" -le 0 ]; then
    echo -e "${RED}Error: --limit must be a valid positive integer${NC}"
    exit 1
fi

# Validate user if specified
VALID_USERS=("admin" "user1" "user2" "user3")
if [ -n "$SPECIFIC_USER" ]; then
    if [[ ! " ${VALID_USERS[@]} " =~ " ${SPECIFIC_USER} " ]]; then
        echo -e "${RED}Error: Invalid user '${SPECIFIC_USER}'${NC}"
        echo -e "${YELLOW}Valid users: ${VALID_USERS[*]}${NC}"
        exit 1
    fi
fi

# Check if Docker is available
if ! command -v docker &> /dev/null; then
    echo -e "${RED}Error: Docker is not installed or not in PATH${NC}"
    echo -e "${YELLOW}Install Docker from: https://docs.docker.com/get-docker/${NC}"
    exit 1
fi

# Check if Docker network exists
if ! docker network inspect "$DOCKER_NETWORK" &> /dev/null; then
    echo -e "${RED}Error: Docker network '$DOCKER_NETWORK' not found${NC}"
    echo -e "${YELLOW}Make sure your services are running (./demo/start.sh)${NC}"
    echo -e "${YELLOW}Or check if the network name has changed:${NC}"
    echo -e "  docker network ls"
    exit 1
fi

# Check if k6 script exists
if [ ! -f "$K6_SCRIPT" ]; then
    echo -e "${RED}Error: k6 script not found at $K6_SCRIPT${NC}"
    exit 1
fi

# Check if terms file exists
if [ ! -f "$TERMS_FILE" ]; then
    echo -e "${RED}Error: Terms file not found at $TERMS_FILE${NC}"
    exit 1
fi

# Load search terms into a single string (will be passed as env var)
SEARCH_TERMS_DATA=$(cat "$TERMS_FILE")
TERM_COUNT=$(echo "$SEARCH_TERMS_DATA" | grep -c '^')

if [ $TERM_COUNT -eq 0 ]; then
    echo -e "${RED}Error: No search terms found in $TERMS_FILE${NC}"
    exit 1
fi

# Display startup message
echo -e "${BLUE}================================${NC}"
echo -e "${BLUE}Starting k6 Load Test${NC}"
echo -e "${BLUE}================================${NC}"
echo -e "${BLUE}Virtual Users (VUs): ${GREEN}${VUS}${NC}"
echo -e "${BLUE}Duration: ${GREEN}${DURATION}${NC}"
echo -e "${BLUE}Request timeout: ${GREEN}${TIMEOUT}${NC}"
echo -e "${BLUE}Result limit: ${GREEN}${LIMIT}${NC}"
echo -e "${BLUE}Search terms: ${GREEN}${TERM_COUNT}${NC}"
if [ -n "$SPECIFIC_USER" ]; then
    echo -e "${BLUE}User mode: ${GREEN}Fixed (${SPECIFIC_USER})${NC}"
else
    echo -e "${BLUE}User mode: ${GREEN}Random rotation${NC}"
fi
echo -e "${BLUE}Stack: ${GREEN}${STACK_NAME}${NC}"
echo -e "${BLUE}Target: ${GREEN}${BASE_URL}${API_PATH}${NC}"
echo -e "${BLUE}Network: ${GREEN}${DOCKER_NETWORK}${NC}"
echo -e "${BLUE}================================${NC}"
echo ""
echo -e "${YELLOW}Pulling k6 Docker image (if needed)...${NC}"
echo ""

# Run k6 via Docker on the same network as NGINX
# This allows k6 to reach nginx by container name
docker run --rm -i \
    --network "$DOCKER_NETWORK" \
    -v "$SCRIPT_DIR:/scripts" \
    -e VUS="$VUS" \
    -e DURATION="$DURATION" \
    -e TIMEOUT="$TIMEOUT" \
    -e LIMIT="$LIMIT" \
    -e SPECIFIC_USER="$SPECIFIC_USER" \
    -e BASE_URL="$BASE_URL" \
    -e API_PATH="$API_PATH" \
    -e SEARCH_TERMS="$SEARCH_TERMS_DATA" \
    grafana/k6 run /scripts/search-load-test.js

echo ""
echo -e "${GREEN}Load test completed!${NC}"
