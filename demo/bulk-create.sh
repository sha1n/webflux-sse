#!/bin/bash

# Bulk event creation script with random permission assignment
# Usage: ./bulk-create.sh <number_of_events>

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Check if number argument is provided
if [ -z "$1" ]; then
    echo -e "${RED}Error: Please provide the number of events to create${NC}"
    echo "Usage: $0 <number_of_events>"
    exit 1
fi

# Validate that argument is a positive number
if ! [[ "$1" =~ ^[0-9]+$ ]] || [ "$1" -le 0 ]; then
    echo -e "${RED}Error: Please provide a valid positive number${NC}"
    exit 1
fi

EVENT_COUNT=$1
BASE_URL="http://localhost/api/v1"

echo -e "${BLUE}Creating $EVENT_COUNT events with random permissions...${NC}"
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

    # Step 1: Create the event
    response=$(curl -s -X POST "$BASE_URL/events" \
        -H "Content-Type: application/json" \
        -d "{\"title\": \"event $i\", \"description\": null}")

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
