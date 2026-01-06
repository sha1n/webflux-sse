#!/bin/bash

# Search load testing script that spawns multiple workers to continuously search
# Usage: ./search-load-test.sh --count <number_of_workers>

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Default values
CLIENT_COUNT=""
CALLS_PER_CLIENT=""
SPECIFIC_USER=""
LIMIT=200
STACK=""
BASE_URL="http://localhost"
TERMS_FILE="$(dirname "$0")/search-terms.txt"

# Array to track client PIDs (running in parallel)
CLIENT_PIDS=()
MONITOR_PID=""

# Temporary file for timing data
TIMING_FILE=$(mktemp)

# Function to display usage
usage() {
    echo -e "${BLUE}Usage: $0 --clients <number> --stack <wf|vt> [--calls-per-client <number>] [--user <user_id>] [--limit <number>]${NC}"
    echo -e "  ${GREEN}--clients${NC}           Number of concurrent search clients (required)"
    echo -e "                       Clients run in parallel to simulate load"
    echo -e "  ${GREEN}--stack${NC}             Backend stack to test: 'wf' (WebFlux) or 'vt' (Virtual Threads) (required)"
    echo -e "  ${GREEN}--calls-per-client${NC}  Number of search calls each client makes (optional)"
    echo -e "                       If not specified, clients run continuously until stopped"
    echo -e "  ${GREEN}--user${NC}              Specific user ID to search as (optional)"
    echo -e "                       Valid users: admin, user1, user2, user3"
    echo -e "                       If not specified, clients will randomly rotate through all users"
    echo -e "  ${GREEN}--limit${NC}             Maximum number of results per search (optional, default: 200)"
    echo ""
    echo "Examples:"
    echo "  $0 --clients 5 --stack wf                              # 5 clients against WebFlux, continuous"
    echo "  $0 --clients 20 --stack vt --calls-per-client 100      # 20 clients against VT, 100 calls each (2000 total)"
    echo "  $0 --clients 10 --stack wf --calls-per-client 50 --user admin  # 10 clients as admin"
    echo "  $0 --clients 5 --stack vt --limit 500                  # 5 clients with limit of 500 results"
    exit 1
}

# Parse named arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --clients)
            CLIENT_COUNT="$2"
            shift 2
            ;;
        --stack)
            STACK="$2"
            shift 2
            ;;
        --calls-per-client)
            CALLS_PER_CLIENT="$2"
            shift 2
            ;;
        --user)
            SPECIFIC_USER="$2"
            shift 2
            ;;
        --limit)
            LIMIT="$2"
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
if [ -z "$CLIENT_COUNT" ]; then
    echo -e "${RED}Error: --clients argument is required${NC}"
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

# Validate that client count is a positive number
if ! [[ "$CLIENT_COUNT" =~ ^[0-9]+$ ]] || [ "$CLIENT_COUNT" -le 0 ]; then
    echo -e "${RED}Error: --clients must be a valid positive integer${NC}"
    exit 1
fi

# Validate calls per client if specified
if [ -n "$CALLS_PER_CLIENT" ]; then
    if ! [[ "$CALLS_PER_CLIENT" =~ ^[0-9]+$ ]] || [ "$CALLS_PER_CLIENT" -le 0 ]; then
        echo -e "${RED}Error: --calls-per-client must be a valid positive integer${NC}"
        exit 1
    fi
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

# Check for required tools
if ! command -v bc &> /dev/null; then
    echo -e "${RED}Error: 'bc' is not installed${NC}"
    echo -e "${YELLOW}Install it with:${NC}"
    echo -e "  ${GREEN}macOS:${NC}   brew install bc"
    echo -e "  ${GREEN}Ubuntu:${NC}  sudo apt-get install bc"
    exit 1
fi

# Check if terms file exists
if [ ! -f "$TERMS_FILE" ]; then
    echo -e "${RED}Error: Terms file not found at $TERMS_FILE${NC}"
    exit 1
fi

# Load search terms into array (portable method)
SEARCH_TERMS=()
while IFS= read -r line; do
    # Skip empty lines
    if [ -n "$line" ]; then
        SEARCH_TERMS+=("$line")
    fi
done < "$TERMS_FILE"
TERM_COUNT=${#SEARCH_TERMS[@]}

if [ $TERM_COUNT -eq 0 ]; then
    echo -e "${RED}Error: No search terms found in $TERMS_FILE${NC}"
    exit 1
fi

echo -e "${BLUE}Loaded ${TERM_COUNT} search terms from $TERMS_FILE${NC}"

# Array of user IDs to rotate through
USER_IDS=("user1" "user2" "user3" "admin")
USER_COUNT=${#USER_IDS[@]}

# Function to calculate percentiles from timing data
calculate_stats() {
    if [ ! -f "$TIMING_FILE" ] || [ ! -s "$TIMING_FILE" ]; then
        echo "0|0|0|0"  # count|p50|p90|avg
        return
    fi

    # Use awk to calculate stats (handles the file reading and sorting efficiently)
    awk '
    {
        times[NR] = $1
        sum += $1
    }
    END {
        if (NR == 0) {
            print "0|0|0|0"
            exit
        }

        # Sort array
        n = asort(times)

        # Calculate percentiles
        p50_idx = int(n * 0.50)
        p90_idx = int(n * 0.90)
        if (p50_idx < 1) p50_idx = 1
        if (p90_idx < 1) p90_idx = 1

        p50 = times[p50_idx]
        p90 = times[p90_idx]
        avg = sum / n

        printf "%d|%.3f|%.3f|%.3f\n", n, p50, p90, avg
    }
    ' "$TIMING_FILE"
}

# Function to display statistics
display_stats() {
    local stats=$(calculate_stats)
    IFS='|' read -r count p50 p90 avg <<< "$stats"

    if [ "$count" -eq 0 ]; then
        return
    fi

    echo -e "${BLUE}================================${NC}"
    echo -e "${BLUE}Query Statistics${NC}"
    echo -e "${BLUE}================================${NC}"
    echo -e "${BLUE}Total queries: ${GREEN}${count}${NC}"
    echo -e "${BLUE}Average: ${GREEN}${avg}s${NC}"
    echo -e "${BLUE}p50 (median): ${GREEN}${p50}s${NC}"
    echo -e "${BLUE}p90: ${GREEN}${p90}s${NC}"
    echo -e "${BLUE}================================${NC}"
}

# Function to generate random search query (1-3 terms)
generate_search_query() {
    local num_terms=$((RANDOM % 3 + 1))  # 1 to 3 terms
    local query_parts=()

    for ((i=0; i<num_terms; i++)); do
        local term_index=$((RANDOM % TERM_COUNT))
        query_parts+=("${SEARCH_TERMS[$term_index]}")
    done

    # Join with spaces
    local query=$(IFS=' '; echo "${query_parts[*]}")
    echo "$query"
}

# Client function that continuously searches (runs in parallel)
search_client() {
    local client_id=$1
    local max_calls=$2
    local searches_done=0
    local color_codes=("$CYAN" "$GREEN" "$YELLOW" "$BLUE")
    local color_index=$((client_id % ${#color_codes[@]}))
    local client_color="${color_codes[$color_index]}"

    while true; do
        # Check if we've reached the call limit
        if [ -n "$max_calls" ] && [ $searches_done -ge $max_calls ]; then
            echo -e "${client_color}[Client $client_id]${NC} Completed all $max_calls calls"
            break
        fi
        # Generate random search query
        local query=$(generate_search_query)

        # Use specific user if provided, otherwise pick random user
        local user_id
        if [ -n "$SPECIFIC_USER" ]; then
            user_id="$SPECIFIC_USER"
        else
            local user_index=$((RANDOM % USER_COUNT))
            user_id="${USER_IDS[$user_index]}"
        fi

        # URL encode the query
        local encoded_query=$(echo "$query" | sed 's/ /%20/g')

        local url="${BASE_URL}${API_PATH}?query=${encoded_query}&userId=${user_id}&limit=${LIMIT}"

        echo -e "${client_color}[Client $client_id]${NC} Searching: \"${query}\" as ${user_id} (limit: ${LIMIT})"

        # Time the search (including reading all results)
        # Using SSE endpoint which returns text/event-stream format
        local start_time=$(date +%s.%N)
        local result_count=$(curl -s -N -H "Accept: text/event-stream" "$url" | grep -c "^data:" 2>/dev/null || echo "0")
        local end_time=$(date +%s.%N)

        # Calculate duration
        local duration=$(echo "$end_time - $start_time" | bc)

        # Record timing data (append to file - atomic for single line writes)
        echo "$duration" >> "$TIMING_FILE"

        ((searches_done++))
        echo -e "${client_color}[Client $client_id]${NC} Completed search #${searches_done} - Got ${result_count} results (${duration}s)"

        # Random sleep between searches (0.5 to 2 seconds)
        local sleep_time=$(awk -v min=0.5 -v max=2.0 'BEGIN{srand(); print min+rand()*(max-min)}')
        sleep $sleep_time
    done
}

# Stats monitoring function (runs every 60 seconds)
stats_monitor() {
    while true; do
        sleep 60
        echo ""
        echo -e "${YELLOW}--- Periodic Stats ($(date +%H:%M:%S)) ---${NC}"
        display_stats
        echo ""
    done
}

# Cleanup function to kill all clients
cleanup() {
    echo ""
    echo -e "${YELLOW}Stopping all clients and monitor...${NC}"

    # Kill monitor process
    if [ -n "$MONITOR_PID" ] && kill -0 $MONITOR_PID 2>/dev/null; then
        kill $MONITOR_PID 2>/dev/null
    fi

    # Kill client processes (all running in parallel)
    for pid in "${CLIENT_PIDS[@]}"; do
        if kill -0 $pid 2>/dev/null; then
            kill $pid 2>/dev/null
        fi
    done
    echo -e "${GREEN}All clients stopped${NC}"

    # Display final stats
    echo ""
    echo -e "${YELLOW}=== Final Statistics ===${NC}"
    display_stats

    # Cleanup temp files
    rm -f "$TIMING_FILE"

    exit 0
}

# Set up trap to cleanup on Ctrl+C
trap cleanup SIGINT SIGTERM

# Display startup message
echo -e "${BLUE}================================${NC}"
echo -e "${BLUE}Starting Search Load Test${NC}"
echo -e "${BLUE}================================${NC}"
echo -e "${BLUE}Parallel clients: ${GREEN}${CLIENT_COUNT}${NC}"
if [ -n "$CALLS_PER_CLIENT" ]; then
    echo -e "${BLUE}Calls per client: ${GREEN}${CALLS_PER_CLIENT}${NC}"
    local total_calls=$((CLIENT_COUNT * CALLS_PER_CLIENT))
    echo -e "${BLUE}Total calls: ${GREEN}${total_calls}${NC}"
else
    echo -e "${BLUE}Calls per client: ${GREEN}Unlimited (continuous)${NC}"
fi
echo -e "${BLUE}Search terms: ${GREEN}${TERM_COUNT}${NC}"
echo -e "${BLUE}Result limit: ${GREEN}${LIMIT}${NC}"
if [ -n "$SPECIFIC_USER" ]; then
    echo -e "${BLUE}User mode: ${GREEN}Fixed (${SPECIFIC_USER})${NC}"
else
    echo -e "${BLUE}User mode: ${GREEN}Random rotation (${USER_IDS[*]})${NC}"
fi
echo -e "${BLUE}Stack: ${GREEN}${STACK_NAME}${NC}"
echo -e "${BLUE}Target: ${GREEN}${BASE_URL}${API_PATH}${NC}"
echo -e "${BLUE}================================${NC}"
echo ""
if [ -z "$CALLS_PER_CLIENT" ]; then
    echo -e "${YELLOW}Press Ctrl+C to stop all clients${NC}"
else
    echo -e "${YELLOW}Clients will stop automatically after completing their calls${NC}"
fi
echo ""

# Spawn clients in parallel
for ((i=1; i<=CLIENT_COUNT; i++)); do
    search_client $i "$CALLS_PER_CLIENT" &
    CLIENT_PIDS+=($!)
    echo -e "${GREEN}Started client $i (PID: ${CLIENT_PIDS[$((i-1))]})${NC}"
    # Small delay to stagger client starts
    sleep 0.05
done

echo ""
echo -e "${BLUE}All ${CLIENT_COUNT} clients are running in parallel...${NC}"

# Start stats monitor in background (only if running continuously)
if [ -z "$CALLS_PER_CLIENT" ]; then
    stats_monitor &
    MONITOR_PID=$!
    echo -e "${BLUE}Stats monitor started (PID: ${MONITOR_PID})${NC}"
    echo -e "${YELLOW}Statistics will be displayed every 60 seconds${NC}"
else
    echo -e "${YELLOW}Waiting for clients to complete...${NC}"
fi
echo ""

# Wait for all clients
wait

# If clients finished naturally (not interrupted), display final stats and cleanup
if [ -n "$CALLS_PER_CLIENT" ]; then
    echo ""
    echo -e "${GREEN}All clients completed!${NC}"
    echo ""
    echo -e "${YELLOW}=== Final Statistics ===${NC}"
    display_stats
    rm -f "$TIMING_FILE"
fi
