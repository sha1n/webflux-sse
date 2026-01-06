#!/bin/bash

# Optimized parallel bulk event creation with real-time progress
# Usage: ./bulk-create-v4.sh --count <number_of_events> [--size <description_size_kb>] [--batch <batch_size>] [--parallel <parallel_batches>]

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
EVENT_COUNT=""
DESC_SIZE_KB=0.5
BATCH_SIZE=100
PARALLEL_BATCHES=10
STACK=""

# Function to display usage
usage() {
    echo -e "${BLUE}Usage: $0 --count <number_of_events> --stack <wf|vt> [--size <description_size_kb>] [--batch <batch_size>] [--parallel <parallel_batches>]${NC}"
    echo -e "  ${GREEN}--count${NC}     Number of events to create (required)"
    echo -e "  ${GREEN}--stack${NC}     Backend stack to use: 'wf' (WebFlux) or 'vt' (Virtual Threads) (required)"
    echo -e "  ${GREEN}--size${NC}      Size of description in KB (optional, default: 0.5 KB)"
    echo -e "  ${GREEN}--batch${NC}     Number of events per bulk request (optional, default: 100)"
    echo -e "  ${GREEN}--parallel${NC}  Number of parallel batch requests (optional, default: 10)"
    echo ""
    echo "Examples:"
    echo "  $0 --count 100000 --stack wf --batch 250 --parallel 20  # Fast: 100k events to WebFlux"
    echo "  $0 --count 1000000 --stack vt --batch 500 --parallel 50 # Very fast: 1M events to VT"
    exit 1
}

# Parse named arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --count)
            EVENT_COUNT="$2"
            shift 2
            ;;
        --stack)
            STACK="$2"
            shift 2
            ;;
        --size)
            DESC_SIZE_KB="$2"
            shift 2
            ;;
        --batch)
            BATCH_SIZE="$2"
            shift 2
            ;;
        --parallel)
            PARALLEL_BATCHES="$2"
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
if [ -z "$EVENT_COUNT" ]; then
    echo -e "${RED}Error: --count argument is required${NC}"
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

# Set stack name for display
if [[ "$STACK" == "wf" ]]; then
    STACK_NAME="WebFlux"
else
    STACK_NAME="Virtual Threads"
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

# Validate batch size
if ! [[ "$BATCH_SIZE" =~ ^[0-9]+$ ]] || [ "$BATCH_SIZE" -le 0 ]; then
    echo -e "${RED}Error: --batch must be a valid positive integer${NC}"
    exit 1
fi

# Validate parallel batches
if ! [[ "$PARALLEL_BATCHES" =~ ^[0-9]+$ ]] || [ "$PARALLEL_BATCHES" -le 0 ]; then
    echo -e "${RED}Error: --parallel must be a valid positive integer${NC}"
    exit 1
fi

# Check if GNU parallel is installed
if ! command -v parallel &> /dev/null; then
    echo -e "${RED}Error: GNU parallel is not installed${NC}"
    echo -e "${YELLOW}Install it with:${NC}"
    echo -e "  ${GREEN}macOS:${NC}   brew install parallel"
    echo -e "  ${GREEN}Ubuntu:${NC}  sudo apt-get install parallel"
    echo -e "  ${GREEN}CentOS:${NC}  sudo yum install parallel"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Calculate number of batches needed
TOTAL_BATCHES=$(( (EVENT_COUNT + BATCH_SIZE - 1) / BATCH_SIZE ))

echo -e "${BLUE}Creating $EVENT_COUNT events using optimized parallel bulk API...${NC}"
echo -e "${BLUE}Stack: ${GREEN}${STACK_NAME}${NC}"
echo -e "${BLUE}Description size: ${DESC_SIZE_KB} KB${NC}"
echo -e "${BLUE}Batch size: ${BATCH_SIZE} events per request${NC}"
echo -e "${BLUE}Parallel batches: ${PARALLEL_BATCHES} concurrent requests${NC}"
echo -e "${BLUE}Total batches: ${TOTAL_BATCHES}${NC}"
echo ""
echo -e "${YELLOW}Progress will update in real-time...${NC}"
echo ""

# Create a temporary file for results
RESULTS_FILE=$(mktemp)
trap "rm -f $RESULTS_FILE" EXIT

# Record start time
START_TIME=$(date +%s)

# Generate batch commands and run in parallel with progress bar
# For each batch, calculate start event number
for batch_id in $(seq 1 $TOTAL_BATCHES); do
    start_num=$(( (batch_id - 1) * BATCH_SIZE + 1 ))
    echo "$batch_id $start_num $BATCH_SIZE $EVENT_COUNT $DESC_SIZE_KB $STACK"
done | parallel -j $PARALLEL_BATCHES --colsep ' ' --bar "$SCRIPT_DIR/create-batch-optimized.sh {1} {2} {3} {4} {5} {6}" 2>/dev/null > "$RESULTS_FILE"

# Record end time
END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

# Parse results
# Format: batch_id|start_num|end_num|created
events_created=0
batches_completed=0
failed_batches=0

while IFS='|' read -r batch_id start_num end_num created error; do
    if [ -n "$error" ]; then
        ((failed_batches++))
    elif [ -n "$created" ] && [[ "$created" =~ ^[0-9]+$ ]]; then
        events_created=$((events_created + created))
        ((batches_completed++))
    fi
done < "$RESULTS_FILE"

# Calculate events per second
if [ $DURATION -gt 0 ]; then
    events_per_second=$(awk "BEGIN {printf \"%.2f\", $events_created/$DURATION}")
else
    events_per_second="N/A (< 1 second)"
fi

# Display summary
echo ""
echo -e "${GREEN}================================${NC}"
echo -e "${GREEN}Summary${NC}"
echo -e "${GREEN}================================${NC}"
echo -e "${BLUE}Events Created: ${GREEN}$events_created${NC} / $EVENT_COUNT"
echo -e "${BLUE}Batches Completed: ${GREEN}$batches_completed${NC} / ${TOTAL_BATCHES}"
if [ $failed_batches -gt 0 ]; then
    echo -e "${BLUE}Failed Batches: ${RED}$failed_batches${NC}"
fi
echo -e "${BLUE}Duration: ${GREEN}${DURATION}s${NC}"
echo -e "${BLUE}Throughput: ${GREEN}${events_per_second} events/sec${NC}"
echo -e "${GREEN}================================${NC}"
