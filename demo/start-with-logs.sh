#!/bin/bash

# Enhanced start script with logging
# This version captures logs to files for debugging

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$SCRIPT_DIR/logs"

# Create logs directory
mkdir -p "$LOG_DIR"

# Source common functions
source "$SCRIPT_DIR/common.sh"

echo -e "${BLUE}🚀 Starting WebFlux SSE Application with Logging${NC}"
echo -e "${YELLOW}Logs will be saved to: $LOG_DIR${NC}"
echo

# Function to start applications with logging
start_applications() {
    echo -e "${BLUE}🔧 Starting Spring Boot applications...${NC}"

    # Check if Maven is installed
    if ! command -v mvn &> /dev/null; then
        echo -e "${RED}❌ Maven is not installed. Please install Maven and try again.${NC}"
        exit 1
    fi

    # JMX configuration for local monitoring
    JMX_OPTS="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -Djava.rmi.server.hostname=localhost"

    # Start authorization-server on port 8082 with 1GB RAM (JMX port 9010)
    echo -e "${YELLOW}⏳ Starting authorization-server on port 8082 (1GB RAM, JMX: 9010)...${NC}"
    echo -e "${BLUE}   Log file: $LOG_DIR/authorization-server.log${NC}"
    export MAVEN_OPTS="$JMX_OPTS -Dcom.sun.management.jmxremote.port=9010 -Dcom.sun.management.jmxremote.rmi.port=9010 -Xms1024m -Xmx1024m"
    mvn -f "$SCRIPT_DIR/../pom.xml" -pl backend/authorization/authorization-server spring-boot:run \
        > "$LOG_DIR/authorization-server.log" 2>&1 &
    AUTH_PID=$!
    unset MAVEN_OPTS

    # Start search-server-wf (reactive) on port 8081 with 1GB RAM (JMX port 9011)
    echo -e "${YELLOW}⏳ Starting search-server-wf (WebFlux) on port 8081 (1GB RAM, JMX: 9011)...${NC}"
    echo -e "${BLUE}   Log file: $LOG_DIR/search-server-wf.log${NC}"
    export MAVEN_OPTS="$JMX_OPTS -Dcom.sun.management.jmxremote.port=9011 -Dcom.sun.management.jmxremote.rmi.port=9011 -Xms1024m -Xmx1024m"
    mvn -f "$SCRIPT_DIR/../pom.xml" -pl backend/search/search-server-wf spring-boot:run \
        > "$LOG_DIR/search-server-wf.log" 2>&1 &
    SEARCH_PID=$!
    unset MAVEN_OPTS

    # Start search-server-vt on port 8083 with 1GB RAM (JMX port 9012)
    echo -e "${YELLOW}⏳ Starting search-server-vt (Virtual Threads) on port 8083 (1GB RAM, JMX: 9012)...${NC}"
    echo -e "${BLUE}   Log file: $LOG_DIR/search-server-vt.log${NC}"
    export MAVEN_OPTS="$JMX_OPTS -Dcom.sun.management.jmxremote.port=9012 -Dcom.sun.management.jmxremote.rmi.port=9012 -Xms1024m -Xmx1024m"
    mvn -f "$SCRIPT_DIR/../pom.xml" -pl backend/search/search-server-vt spring-boot:run \
        > "$LOG_DIR/search-server-vt.log" 2>&1 &
    SEARCH_VIRTUAL_PID=$!
    unset MAVEN_OPTS

    # Wait for services to start (pass log file paths for better error messages)
    wait_for_service "authorization-server" "8082" "http://localhost:8082/api/v1/permissions" "$LOG_DIR/authorization-server.log"
    wait_for_service "search-server-wf (WebFlux)" "8081" "http://localhost:8081/api/v1/events" "$LOG_DIR/search-server-wf.log"
    wait_for_service "search-server-vt (Virtual Threads)" "8083" "http://localhost:8083/api/v1/events" "$LOG_DIR/search-server-vt.log"
}

# Function to display final information
show_info() {
    echo
    echo -e "${GREEN}🎉 Setup complete!${NC}"
    echo
    echo -e "${BLUE}📱 Application URLs:${NC}"
    echo -e "   🌐 Main UI (Nginx Gateway): ${YELLOW}http://localhost${NC}"
    echo -e "   📡 SSE Stream: ${YELLOW}http://localhost/api/v1/events${NC}"
    echo -e "   🔍 Search: ${YELLOW}http://localhost/search.html${NC}"
    echo -e "   🔐 Permissions: ${YELLOW}http://localhost/permissions.html${NC}"
    echo
    echo -e "${BLUE}🔧 Backend Services (Direct Access):${NC}"
    echo -e "   Search Service (WebFlux): ${YELLOW}http://localhost:8081${NC}"
    echo -e "   Authorization Service: ${YELLOW}http://localhost:8082${NC}"
    echo -e "   Search Service (Virtual Threads): ${YELLOW}http://localhost:8083${NC}"
    echo
    echo -e "${BLUE}📋 Log Files:${NC}"
    echo -e "   Search Service (WebFlux): ${YELLOW}tail -f $LOG_DIR/search-server-wf.log${NC}"
    echo -e "   Authorization Service: ${YELLOW}tail -f $LOG_DIR/authorization-server.log${NC}"
    echo -e "   Search Service (VT): ${YELLOW}tail -f $LOG_DIR/search-server-vt.log${NC}"
    echo -e "   Monitor script: ${YELLOW}./monitor-logs.sh${NC}"
    echo
    echo -e "${BLUE}📊 JMX Monitoring:${NC}"
    echo -e "   Authorization Server: ${YELLOW}localhost:9010${NC}"
    echo -e "   Search Server (WebFlux): ${YELLOW}localhost:9011${NC}"
    echo -e "   Search Server (Virtual Threads): ${YELLOW}localhost:9012${NC}"
    echo
    echo -e "${BLUE}📋 Available Commands:${NC}"
    echo -e "   🛑 Stop all services: ${YELLOW}./stop.sh${NC}"
    echo -e "   📄 View logs: ${YELLOW}tail -f $LOG_DIR/*.log${NC}"
    echo
    echo -e "${GREEN}Press Ctrl+C to stop all applications${NC}"
}

# Set trap to cleanup on script exit
trap cleanup INT TERM

# Main execution
check_docker
"$SCRIPT_DIR/../frontend/configure-nginx-host.sh"
start_database
start_applications
start_jvisualvm
show_info

# Keep the script running - wait for services
# This will exit when all services are stopped (e.g., by stop.sh or Ctrl+C)
# Check for both Maven processes (spring-boot:run) and Java processes (Application classes)
while pgrep -f "authorization-server.*spring-boot:run" > /dev/null || \
      pgrep -f "search-server-wf.*spring-boot:run" > /dev/null || \
      pgrep -f "search-server-vt.*spring-boot:run" > /dev/null || \
      pgrep -f "AuthorizationServiceApplication" > /dev/null || \
      pgrep -f "SearchServiceWfApplication\|SearchServiceVtApplication" > /dev/null; do
    sleep 2
done

echo
echo -e "${YELLOW}⚠️  All services have stopped${NC}"
exit 0
