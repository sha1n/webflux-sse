#!/bin/bash

# Optimized single batch creation script with faster description generation
# Usage: ./create-batch-optimized.sh <batch_id> <start_event_num> <batch_size> <total_count> <desc_size_kb>

BATCH_ID=$1
START_NUM=$2
BATCH_SIZE=$3
TOTAL_COUNT=$4
DESC_SIZE_KB=$5

if [ -z "$BATCH_ID" ] || [ -z "$START_NUM" ] || [ -z "$BATCH_SIZE" ] || [ -z "$TOTAL_COUNT" ] || [ -z "$DESC_SIZE_KB" ]; then
    echo "Error: Missing required arguments"
    exit 1
fi

BASE_URL="http://localhost/api/v1"

# Calculate target description size in bytes
DESC_SIZE_BYTES=$(awk "BEGIN {printf \"%.0f\", $DESC_SIZE_KB * 1024}")

# Function to generate random text with randomized word order
generate_description_fast() {
    local target_size=$1

    # Pool of words to randomly select from
    local words=("data" "process" "event" "system" "user" "request" "response" "service" "operation"
                 "network" "database" "cache" "queue" "stream" "buffer" "thread" "async" "sync"
                 "authentication" "authorization" "validation" "encryption" "parsing" "serialization"
                 "transformation" "mapping" "filtering" "application" "session" "transaction"
                 "function" "method" "component" "module" "interface" "implementation" "configuration"
                 "parameter" "variable" "value" "result" "error" "exception" "handler" "controller"
                 "repository" "entity" "model" "view" "decryption" "encoding")

    local word_count=${#words[@]}
    local description=""
    local current_size=0

    # Build description by randomly selecting words until we reach target size
    while [ $current_size -lt $target_size ]; do
        # Get random word index
        local random_index=$(( RANDOM % word_count ))
        local word="${words[$random_index]}"

        # Add word with space
        if [ -z "$description" ]; then
            description="$word"
        else
            description="$description $word"
        fi

        current_size=${#description}
    done

    # Truncate to exact target size
    echo "${description:0:$target_size}"
}

# Build batch payload
json_payload='{"events":['

END_NUM=$((START_NUM + BATCH_SIZE - 1))
if [ $END_NUM -gt $TOTAL_COUNT ]; then
    END_NUM=$TOTAL_COUNT
fi

for i in $(seq $START_NUM $END_NUM); do
    # Generate description using fast method
    description=$(generate_description_fast $DESC_SIZE_BYTES)

    # Simple escape (descriptions don't have special chars with this method)
    if [ $i -ne $START_NUM ]; then
        json_payload="${json_payload},"
    fi

    json_payload="${json_payload}{\"title\":\"Event $i of $TOTAL_COUNT (~${DESC_SIZE_KB}kb)\",\"description\":\"${description}\"}"
done

json_payload="${json_payload}]}"

# Send bulk request
response=$(curl -s -X POST "$BASE_URL/events/bulk" \
    -H "Content-Type: application/json" \
    -d "$json_payload")

if [ $? -eq 0 ]; then
    # Extract all event IDs from response
    event_ids=($(echo "$response" | grep -o '"id":[0-9]*' | grep -o '[0-9]*'))
    created=${#event_ids[@]}

    if [ $created -gt 0 ]; then
        # Build permission arrays based on event ID
        user1_ids=()
        user2_ids=()
        user3_ids=()
        admin_ids=()

        for event_id in "${event_ids[@]}"; do
            # user1: every 1000th ID
            if [ $((event_id % 1000)) -eq 0 ]; then
                user1_ids+=($event_id)
            fi

            # user2: every 2000th ID
            if [ $((event_id % 2000)) -eq 0 ]; then
                user2_ids+=($event_id)
            fi

            # user3: every 3000th ID
            if [ $((event_id % 3000)) -eq 0 ]; then
                user3_ids+=($event_id)
            fi

            # admin: all events
            admin_ids+=($event_id)
        done

        # Grant permissions in bulk (one request per user with their event IDs)
        if [ ${#user1_ids[@]} -gt 0 ]; then
            user1_json=$(printf '%s\n' "${user1_ids[@]}" | jq -R . | jq -s -c .)
            curl -s -X POST "$BASE_URL/permissions/bulk" \
                -H "Content-Type: application/json" \
                -d "{\"userId\":\"user1\",\"eventIds\":$user1_json}" > /dev/null
        fi

        if [ ${#user2_ids[@]} -gt 0 ]; then
            user2_json=$(printf '%s\n' "${user2_ids[@]}" | jq -R . | jq -s -c .)
            curl -s -X POST "$BASE_URL/permissions/bulk" \
                -H "Content-Type: application/json" \
                -d "{\"userId\":\"user2\",\"eventIds\":$user2_json}" > /dev/null
        fi

        if [ ${#user3_ids[@]} -gt 0 ]; then
            user3_json=$(printf '%s\n' "${user3_ids[@]}" | jq -R . | jq -s -c .)
            curl -s -X POST "$BASE_URL/permissions/bulk" \
                -H "Content-Type: application/json" \
                -d "{\"userId\":\"user3\",\"eventIds\":$user3_json}" > /dev/null
        fi

        # Admin gets all events (always has IDs)
        admin_json=$(printf '%s\n' "${admin_ids[@]}" | jq -R . | jq -s -c .)
        curl -s -X POST "$BASE_URL/permissions/bulk" \
            -H "Content-Type: application/json" \
            -d "{\"userId\":\"admin\",\"eventIds\":$admin_json}" > /dev/null
    fi

    echo "$BATCH_ID|$START_NUM|$END_NUM|$created"
else
    echo "$BATCH_ID|$START_NUM|$END_NUM|0|ERROR"
fi
