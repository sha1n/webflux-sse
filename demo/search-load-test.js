import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// Custom metrics
const resultCount = new Trend('result_count');
const searchErrors = new Counter('search_errors');

// Configuration from environment variables
const VUS = __ENV.VUS || '10';
const DURATION = __ENV.DURATION || '60s';
const TIMEOUT = __ENV.TIMEOUT || '60s';
const LIMIT = __ENV.LIMIT || '200';
const SPECIFIC_USER = __ENV.SPECIFIC_USER || '';
const BASE_URL = __ENV.BASE_URL || 'http://localhost';
const API_PATH = __ENV.API_PATH || '/api-reactive/rpc/v1/search';

// Load search terms from the file content passed via environment
const TERMS_DATA = __ENV.SEARCH_TERMS || 'technology\nhealth\nfinance\neducation\nscience';
const searchTerms = TERMS_DATA.split('\n').filter(term => term.trim().length > 0);

// User IDs to rotate through
const userIds = ['user1', 'user2', 'user3', 'admin'];

// k6 test configuration
export const options = {
  // Gradual ramp-up to avoid overwhelming the server
  stages: [
    { duration: '10s', target: Math.ceil(parseInt(VUS) * 0.3) },  // Ramp up to 30% of target VUs
    { duration: '10s', target: Math.ceil(parseInt(VUS) * 0.6) },  // Ramp up to 60% of target VUs
    { duration: '10s', target: parseInt(VUS) },                    // Ramp up to 100% of target VUs
    { duration: DURATION, target: parseInt(VUS) },                 // Hold at target for specified duration
    { duration: '10s', target: 0 },                                // Ramp down gracefully
  ],
  // Global HTTP timeout settings
  noConnectionReuse: false,  // Enable connection reuse (keepalive)
  userAgent: 'k6-load-test',
  insecureSkipTLSVerify: true,
  // Disable thresholds - let test run to completion regardless of performance
  noVUConnectionReuse: false,
};

// Generate random search query (1-3 terms)
function generateSearchQuery() {
  const numTerms = Math.floor(Math.random() * 3) + 1; // 1 to 3 terms
  const queryParts = [];

  for (let i = 0; i < numTerms; i++) {
    const termIndex = Math.floor(Math.random() * searchTerms.length);
    queryParts.push(searchTerms[termIndex]);
  }

  return queryParts.join(' ');
}

// Main test function - runs for each virtual user iteration
export default function () {
  // Generate random search query
  const query = generateSearchQuery();

  // Select user
  const userId = SPECIFIC_USER || userIds[Math.floor(Math.random() * userIds.length)];

  // Build URL - uses stack-specific API path (WebFlux or Virtual Threads)
  const url = `${BASE_URL}${API_PATH}`;

  // Build request body - matches UI exactly
  const payload = JSON.stringify({
    query: query || null,
    userId: userId,
    limit: parseInt(LIMIT)
  });

  // Make POST request with SSE Accept header - matches search-sse.html exactly
  const res = http.post(url, payload, {
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream',  // SSE format (same as search-sse.html)
    },
    tags: { name: 'search_request' },
    timeout: TIMEOUT,
  });

  // Check response
  const success = check(res, {
    'status is 200': (r) => r.status === 200,
    'response has content': (r) => r.body && r.body.length > 0,
  });

  if (!success) {
    searchErrors.add(1);
    if (res.error) {
      console.log(`Error for query "${query}" as ${userId}: ${res.error}`);
    }
  }

  // Count results (lines starting with "data:")
  if (res.body) {
    const lines = res.body.split('\n');
    const count = lines.filter(line => line.startsWith('data:')).length;
    resultCount.add(count);
  } else if (res.status !== 200) {
    console.log(`Non-200 status ${res.status} for query "${query}" as ${userId}`);
  }

  // Very small sleep between requests (50-150ms) to avoid overwhelming the server
  // but still generate significant load
  sleep(Math.random() * 0.1 + 0.05);
}

// Setup function - runs once at the start
export function setup() {
  console.log(`Starting k6 load test:`);
  console.log(`  Virtual Users (concurrent clients): ${VUS} (with 30s gradual ramp-up)`);
  console.log(`  Duration: ${DURATION} at peak load`);
  console.log(`  Request Timeout: ${TIMEOUT}`);
  console.log(`  Result Limit: ${LIMIT}`);
  console.log(`  Search Terms: ${searchTerms.length} loaded`);
  console.log(`  User Mode: ${SPECIFIC_USER || 'Random rotation'}`);
  console.log(`  Target: ${BASE_URL}`);
  console.log(`  Endpoint: POST /api/rpc/v1/search (SSE format, same as UI)`);
  console.log(`  Strategy: Gradual ramp-up with connection reuse enabled`);
  console.log('');
}

// Teardown function - runs once at the end
export function teardown(data) {
  console.log('\nLoad test completed!');
}
