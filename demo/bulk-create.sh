#!/bin/bash

# Bulk event creation script with random permission assignment
# Usage: ./bulk-create.sh --count <number_of_events> [--size <description_size_kb>]
#   --count: Number of events to create (required)
#   --size:  Size of description in KB (optional, default: 0.5 KB = 500 bytes)

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
EVENT_COUNT=""
DESC_SIZE_KB=0.5

# Function to display usage
usage() {
    echo -e "${BLUE}Usage: $0 --count <number_of_events> [--size <description_size_kb>]${NC}"
    echo -e "  ${GREEN}--count${NC}  Number of events to create (required)"
    echo -e "  ${GREEN}--size${NC}   Size of description in KB (optional, default: 0.5 KB)"
    echo ""
    echo "Examples:"
    echo "  $0 --count 10                # Create 10 events with 500-byte descriptions"
    echo "  $0 --count 100 --size 2      # Create 100 events with 2KB descriptions"
    echo "  $0 --size 1 --count 50       # Order doesn't matter"
    exit 1
}

# Parse named arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --count)
            EVENT_COUNT="$2"
            shift 2
            ;;
        --size)
            DESC_SIZE_KB="$2"
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

# Validate required argument
if [ -z "$EVENT_COUNT" ]; then
    echo -e "${RED}Error: --count argument is required${NC}"
    usage
fi

# Validate that event count is a positive number
if ! [[ "$EVENT_COUNT" =~ ^[0-9]+$ ]] || [ "$EVENT_COUNT" -le 0 ]; then
    echo -e "${RED}Error: --count must be a valid positive integer${NC}"
    exit 1
fi

# Validate description size
if ! [[ "$DESC_SIZE_KB" =~ ^[0-9]+\.?[0-9]*$ ]]; then
    echo -e "${RED}Error: --size must be a positive number${NC}"
    exit 1
fi

# Calculate target description size in bytes
DESC_SIZE_BYTES=$(awk "BEGIN {printf \"%.0f\", $DESC_SIZE_KB * 1024}")

BASE_URL="http://localhost/api/v1"

# Function to generate random text of approximately specified size
generate_description() {
    local target_size=$1
    local current_size=0
    local description=""

    # Array of random words for more natural text
    local words=("system" "process" "data" "event" "service" "application" "request" "response"
                 "user" "session" "transaction" "operation" "function" "method" "component" "module"
                 "interface" "implementation" "configuration" "parameter" "variable" "value" "result"
                 "error" "exception" "handler" "controller" "repository" "entity" "model" "view"
                 "network" "database" "cache" "queue" "stream" "buffer" "thread" "async" "sync"
                 "authentication" "authorization" "validation" "encryption" "decryption" "encoding"
                 "parsing" "serialization" "deserialization" "transformation" "mapping" "filtering")

    local word_count=${#words[@]}

    # Generate text until we reach approximately the target size
    while [ $current_size -lt $target_size ]; do
        # Get random word
        local random_index=$((RANDOM % word_count))
        local word="${words[$random_index]}"

        # Add word and space
        if [ -z "$description" ]; then
            description="$word"
        else
            description="$description $word"
        fi

        current_size=${#description}
    done

    echo "$description"
}

echo -e "${BLUE}Creating $EVENT_COUNT events with random permissions...${NC}"
echo -e "${BLUE}Description size: ${DESC_SIZE_KB} KB (approximately ${DESC_SIZE_BYTES} bytes)${NC}"
echo

# Counters for permission distribution
user1_count=0
user2_count=0
user3_count=0
admin_count=0
events_created=0

# Loop to create events
for i in $(seq 1 $EVENT_COUNT); do
    echo -e "${YELLOW}Creating event $i of $EVENT_COUNT...${NC}"

    # Generate description
    description=$(generate_description $DESC_SIZE_BYTES)

    # Step 1: Create the event
    response=$(curl -s -X POST "$BASE_URL/events" \
        -H "Content-Type: application/json" \
        -d "{\"title\": \"Event $i of $EVENT_COUNT (~${DESC_SIZE_KB}kb)\", \"description\": \"$description\"}")

    if [ $? -ne 0 ]; then
        echo -e "${RED}Failed to create event $i${NC}"
        continue
    fi

    # Extract event ID from response
    event_id=$(echo $response | grep -o '"id":[0-9]*' | grep -o '[0-9]*')

    if [ -z "$event_id" ]; then
        echo -e "${RED}Failed to get event ID for event $i${NC}"
        continue
    fi

    echo -e "${GREEN}Created event $i with ID: $event_id${NC}"
    ((events_created++))

    # Step 2: Assign permissions based on event ID (deterministic)
    # user1: 1 in 1000 (IDs where ID % 1000 == 0)
    # user2: 1 in 2000 (IDs where ID % 2000 == 0)
    # user3: 1 in 3000 (IDs where ID % 3000 == 0)

    # Grant permission to user1 (every 1000th ID)
    if [ $((event_id % 1000)) -eq 0 ]; then
        curl -s -X POST "$BASE_URL/permissions/bulk" \
            -H "Content-Type: application/json" \
            -d "{\"userId\": \"user1\", \"eventIds\": [$event_id]}" > /dev/null
        ((user1_count++))
        echo "  ✓ Granted permission to user1"
    fi

    # Grant permission to user2 (every 2000th ID)
    if [ $((event_id % 2000)) -eq 0 ]; then
        curl -s -X POST "$BASE_URL/permissions/bulk" \
            -H "Content-Type: application/json" \
            -d "{\"userId\": \"user2\", \"eventIds\": [$event_id]}" > /dev/null
        ((user2_count++))
        echo "  ✓ Granted permission to user2"
    fi

    # Grant permission to user3 (every 3000th ID)
    if [ $((event_id % 3000)) -eq 0 ]; then
        curl -s -X POST "$BASE_URL/permissions/bulk" \
            -H "Content-Type: application/json" \
            -d "{\"userId\": \"user3\", \"eventIds\": [$event_id]}" > /dev/null
        ((user3_count++))
        echo "  ✓ Granted permission to user3"
    fi

    # Admin always gets access
    curl -s -X POST "$BASE_URL/permissions/bulk" \
        -H "Content-Type: application/json" \
        -d "{\"userId\": \"admin\", \"eventIds\": [$event_id]}" > /dev/null
    ((admin_count++))
    echo "  ✓ Granted permission to admin"

    echo
done

# Calculate totals
total_permissions=$((user1_count + user2_count + user3_count + admin_count))

# Display summary
echo -e "${GREEN}================================${NC}"
echo -e "${GREEN}Summary${NC}"
echo -e "${GREEN}================================${NC}"
echo -e "${BLUE}Events Created: ${GREEN}$events_created${NC}"
echo
echo -e "${BLUE}Permission Distribution:${NC}"
echo -e "  user1:  $user1_count ($(awk "BEGIN {printf \"%.0f\", $user1_count/$events_created*100}")% of events)"
echo -e "  user2:  $user2_count ($(awk "BEGIN {printf \"%.0f\", $user2_count/$events_created*100}")% of events)"
echo -e "  user3:  $user3_count ($(awk "BEGIN {printf \"%.0f\", $user3_count/$events_created*100}")% of events)"
echo -e "  admin:  $admin_count (100% of events)"
echo
echo -e "${BLUE}Total Permissions: ${GREEN}$total_permissions${NC}"
echo -e "${GREEN}================================${NC}"
