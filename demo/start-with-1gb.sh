#!/bin/bash

# Start script with 1GB heap allocation for each service
# This script sets JAVA_TOOL_OPTIONS before starting services

# Set JVM options for 1GB heap
export JAVA_TOOL_OPTIONS="-Xms1024m -Xmx1024m"

echo "Starting services with 1GB heap allocation..."
echo "JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS"
echo ""

# Run the normal start script
./start.sh
