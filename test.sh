#!/bin/bash

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}🧪 Running Integration Tests${NC}"
echo

# Check if Maven is installed
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}❌ Maven is not installed. Please install Maven and try again.${NC}"
    exit 1
fi

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo -e "${RED}❌ Docker is not running. Please start Docker to run Testcontainers.${NC}"
    exit 1
fi

echo -e "${YELLOW}⏳ Running integration tests with Testcontainers PostgreSQL...${NC}"
if mvn verify; then
    echo
    echo -e "${GREEN}🎉 All tests passed!${NC}"
    echo
    echo -e "${BLUE}📋 Test Summary:${NC}"
    echo -e "   ✅ REST API endpoint tests (PostgreSQL)"
    echo -e "   ✅ SSE streaming endpoint tests (PostgreSQL)" 
    echo -e "   ✅ Event ordering and validation tests (PostgreSQL)"
    echo -e "   ✅ Testcontainers PostgreSQL integration tests"
    echo
    echo -e "${GREEN}The API is working correctly with real PostgreSQL database!${NC}"
else
    echo
    echo -e "${RED}❌ Some tests failed. Check the output above for details.${NC}"
    exit 1
fi