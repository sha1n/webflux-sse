#!/bin/bash

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Source common functions
source "$SCRIPT_DIR/common.sh"

echo -e "${BLUE}🚀 Starting WebFlux SSE Application${NC}"
echo

# Function to start applications
start_applications() {
    echo -e "${BLUE}🔧 Starting Spring Boot applications...${NC}"

    # Check if Maven is installed
    if ! command -v mvn &> /dev/null; then
        echo -e "${RED}❌ Maven is not installed. Please install Maven and try again.${NC}"
        exit 1
    fi

    # JMX configuration for local monitoring
    JMX_OPTS="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -Djava.rmi.server.hostname=localhost"

    # Start authorization-server on port 8082 (JMX port 9010)
    echo -e "${YELLOW}⏳ Starting authorization-server on port 8082 (JMX: 9010)...${NC}"
    export MAVEN_OPTS="$JMX_OPTS -Dcom.sun.management.jmxremote.port=9010 -Dcom.sun.management.jmxremote.rmi.port=9010"
    mvn -f "$SCRIPT_DIR/../pom.xml" -pl backend/authorization/authorization-server spring-boot:run > /dev/null 2>&1 &
    AUTH_PID=$!
    unset MAVEN_OPTS

    # Start search-server-wf on port 8081 (JMX port 9011)
    echo -e "${YELLOW}⏳ Starting search-server-wf on port 8081 (JMX: 9011)...${NC}"
    export MAVEN_OPTS="$JMX_OPTS -Dcom.sun.management.jmxremote.port=9011 -Dcom.sun.management.jmxremote.rmi.port=9011"
    mvn -f "$SCRIPT_DIR/../pom.xml" -pl backend/search/search-server-wf spring-boot:run > /dev/null 2>&1 &
    SEARCH_PID=$!
    unset MAVEN_OPTS

    # Start search-server-vt on port 8083 (JMX port 9012)
    echo -e "${YELLOW}⏳ Starting search-server-vt on port 8083 (JMX: 9012)...${NC}"
    export MAVEN_OPTS="$JMX_OPTS -Dcom.sun.management.jmxremote.port=9012 -Dcom.sun.management.jmxremote.rmi.port=9012"
    mvn -f "$SCRIPT_DIR/../pom.xml" -pl backend/search/search-server-vt spring-boot:run > /dev/null 2>&1 &
    SEARCH_VIRTUAL_PID=$!
    unset MAVEN_OPTS

    # Wait for services to start
    wait_for_service "authorization-server" "8082" "http://localhost:8082/api/v1/permissions"
    wait_for_service "search-server-wf" "8081" "http://localhost:8081/api/v1/events"
    wait_for_service "search-server-vt" "8083" "http://localhost:8083/api/v1/events"
}

# Function to display final information
show_info() {
    echo
    echo -e "${GREEN}🎉 Setup complete!${NC}"
    echo
    echo -e "${BLUE}📱 Application URLs:${NC}"
    echo -e "   🌐 Main UI (Nginx Gateway): ${YELLOW}http://localhost${NC}"
    echo -e "   📡 SSE Stream: ${YELLOW}http://localhost/api/v1/events${NC}"
    echo -e "   🔍 Search (Reactive): ${YELLOW}http://localhost/search-sse.html${NC}"
    echo -e "   🔍 Search (Virtual Threads): ${YELLOW}http://localhost/search-sse-virtual.html${NC}"
    echo -e "   🔐 Permissions: ${YELLOW}http://localhost/permissions.html${NC}"
    echo
    echo -e "${BLUE}🔧 Backend Services (Direct Access):${NC}"
    echo -e "   Search Service (Reactive): ${YELLOW}http://localhost:8081${NC}"
    echo -e "   Search Service (Virtual Threads): ${YELLOW}http://localhost:8083${NC}"
    echo -e "   Authorization Service: ${YELLOW}http://localhost:8082${NC}"
    echo
    echo -e "${BLUE}📚 API Documentation:${NC}"
    echo -e "   Search Service API (Reactive): ${YELLOW}http://localhost:8081/swagger-ui.html${NC}"
    echo -e "   Search Service API (Virtual Threads): ${YELLOW}http://localhost:8083/swagger-ui.html${NC}"
    echo -e "   Authorization Service API: ${YELLOW}http://localhost:8082/swagger-ui.html${NC}"
    echo
    echo -e "${BLUE}🗃️ Database Connection:${NC}"
    echo -e "   🔗 Host: ${YELLOW}localhost:5432${NC}"
    echo -e "   📂 Database: ${YELLOW}eventdb${NC}"
    echo -e "   👤 User: ${YELLOW}postgres${NC}"
    echo
    echo -e "${BLUE}📊 JMX Monitoring:${NC}"
    echo -e "   Authorization Server: ${YELLOW}localhost:9010${NC}"
    echo -e "   Search Server (WebFlux): ${YELLOW}localhost:9011${NC}"
    echo -e "   Search Server (Virtual Threads): ${YELLOW}localhost:9012${NC}"
    echo
    echo -e "${BLUE}📋 Available Commands:${NC}"
    echo -e "   🛑 Stop all services: ${YELLOW}./stop.sh${NC}"
    echo -e "   🛑 Stop containers: ${YELLOW}docker-compose down${NC}"
    echo -e "   📄 View logs: ${YELLOW}docker-compose logs [postgres|elasticsearch|nginx]${NC}"
    echo -e "   🔍 Connect to database: ${YELLOW}docker-compose exec postgres psql -U postgres -d eventdb${NC}"
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
