# Performance Analysis: Virtual Threads vs WebFlux Implementation

## Executive Summary

The Virtual Threads (VT) implementation exhibits significantly worse performance compared to the WebFlux (WF) implementation, especially when users have sparse permissions on search results. This analysis identifies the root causes and provides recommendations for optimization.

**Key Finding:** The VT implementation uses blocking patterns that prevent optimal parallelization, while the WF implementation leverages true reactive streams with natural backpressure and efficient concurrency control.

---

## Architecture Comparison

### Virtual Threads Implementation (Port 8083)
- **Framework:** Spring MVC with Virtual Threads (Project Loom)
- **HTTP Client:** RestClient with JDK HttpClient
- **Elasticsearch:** Blocking `Stream<EventEntity>` API
- **Concurrency Model:** Manual queue-based Future management
- **Database:** JDBC with HikariCP connection pooling

### WebFlux Implementation (Port 8081)
- **Framework:** Spring WebFlux with Project Reactor
- **HTTP Client:** WebClient with Reactor Netty
- **Elasticsearch:** Reactive `Flux<EventEntity>` API
- **Concurrency Model:** Reactive operators (`flatMapSequential`, `bufferTimeout`)
- **Database:** R2DBC with reactive connection pooling

---

## Critical Performance Bottlenecks in VT Implementation

### 1. **Sequential Batch Processing with Blocking**

**Location:** `SearchService.java:74-105`

```java
return Stream.generate(() -> {
    // ... fills queue with up to CONCURRENCY futures
    if (!futureQueue.isEmpty()) {
        return futureQueue.poll().get(); // ⚠️ BLOCKING CALL
    }
    return null;
})
```

**Problems:**
- `Future.get()` blocks the virtual thread until the **specific** batch completes
- Uses FIFO queue ordering, preventing out-of-order completion
- If batch 1 takes 2 seconds but batch 2 completes in 100ms, batch 2 must still wait
- With sparse permissions, empty batches still incur full latency cost
- Cannot scale beyond the hardcoded `CONCURRENCY = 4` limit

**Impact on Sparse Permissions:**
When a user has 1% permission rate (1 authorized event per 100 searched):
- Must process ~100 batches (20 events each) to get 20 results
- Each batch waits sequentially for permission check
- Total latency = ~100 × avg_batch_latency (no parallelism benefit)

**WebFlux Equivalent:**
```java
searchResults
    .bufferTimeout(20, Duration.ofSeconds(5))
    .flatMapSequential(batch -> checkPermissionsBatch(batch, userId), 4)
```
- Non-blocking: processes up to 4 batches in parallel
- Results emit as soon as authorized events are found
- Backpressure automatically adjusts based on downstream consumption

---

### 2. **Artificial Concurrency Limitation**

**Location:** `SearchService.java:25, 76-77`

```java
private static final int CONCURRENCY = 4; // Match WebFlux concurrency level

while (futureQueue.size() < CONCURRENCY && iterator.hasNext()) {
    // Submit new batch
}
```

**Problems:**
- Hardcoded to 4 concurrent requests
- Queue management adds overhead without flexibility
- Cannot dynamically adjust based on:
  - Permission hit rate (sparse vs dense permissions)
  - Network latency to authorization service
  - Available system resources

**Recommendation:**
Make this configurable via `application.yml`:
```yaml
search-service:
  batch-concurrency: 8  # Increase for better throughput with sparse permissions
```

---

### 3. **RestClient Timeout Enforcement Overhead**

**Location:** `AuthorizationServiceClient.java:37-52`

```java
CompletableFuture<BatchPermissionCheckResponse> future = CompletableFuture.supplyAsync(() -> {
    return restClient.post()
        .uri("/api/v1/permissions/batch-check")
        .body(request)
        .retrieve()
        .toEntity(BatchPermissionCheckResponse.class)
        .getBody();
}, virtualThreadExecutor);

return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
```

**Problems:**
- Wraps blocking RestClient call in CompletableFuture
- Uses separate virtual thread executor for timeout enforcement
- Adds context switching overhead
- `future.get()` blocks the calling virtual thread
- Creates two virtual threads per request (caller + executor)

**Why This Hurts Performance:**
- Each batch permission check spawns 2 virtual threads minimum
- With CONCURRENCY=4, this means 8+ virtual threads active
- Context switching between virtual threads has overhead (small, but accumulates)
- Timeout enforcement via `future.get()` is less efficient than Reactor's timeout operator

**WebFlux Equivalent:**
```java
return webClient.post()
    .uri("/api/v1/permissions/batch-check")
    .bodyValue(request)
    .retrieve()
    .bodyToMono(BatchPermissionCheckResponse.class)
    .timeout(timeout)  // ⚠️ Zero overhead, built into reactive chain
```
- Single subscription, no thread spawning
- Timeout handled by Reactor scheduler
- Non-blocking throughout

---

### 4. **Blocking Elasticsearch Stream API**

**Location:** `EventElasticsearchRepository.java:32, 51`

```java
Stream<EventEntity> searchByTitleOrDescription(String query);
Stream<EventEntity> searchByExactPhrase(String phrase);
```

**Problems:**
- `Stream<EventEntity>` forces blocking iteration
- Must fetch batches from Elasticsearch synchronously
- Cannot interleave Elasticsearch fetching with permission checking
- Iterator blocks until next page is ready

**Impact:**
- Elasticsearch pagination (using search_after) happens synchronously
- If Elasticsearch takes 500ms to fetch page 5, permission checking pauses
- No overlap between "fetch from ES" and "check permissions" operations

**WebFlux Equivalent:**
```java
Flux<EventEntity> searchByTitleOrDescription(String query);
```
- Non-blocking reactive queries
- Fetches next page while processing current page
- Naturally pipelines ES fetching → batching → permission checking

---

### 5. **Manual Backpressure with Synchronization**

**Location:** `SearchController.java:75-85`

```java
synchronized (lock) {
    while (inFlight.get() >= maxInFlight) {
        try {
            lock.wait(100); // ⚠️ Blocks virtual thread for 100ms
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for backpressure", e);
        }
    }
    inFlight.incrementAndGet();
}
```

**Problems:**
- `synchronized` blocks create contention, even with virtual threads
- `lock.wait(100)` wastes CPU cycles on timeout-based polling
- `maxInFlight=10` is arbitrary and not tuned to actual client consumption rate
- Comment explicitly mentions: "TODO shai: IMPORTANT this should be questioned and possibly tuned!!!"

**Why This Hurts:**
- Slow clients cause producer (search pipeline) to block frequently
- Fast clients don't benefit from higher throughput (capped at 10 in-flight)
- Synchronization overhead accumulates with many concurrent searches

**WebFlux Equivalent:**
```java
return searchService.searchEventsForUser(query, userId, limit)
    .map(event -> ServerSentEvent.<Event>builder().data(event).build());
```
- Zero synchronization primitives
- Backpressure handled by Reactor's request/pull model
- Adapts dynamically to client consumption speed
- No polling, no wasted CPU cycles

---

### 6. **Connection Pooling Limitations**

**VT Configuration (`RestClientConfig.java`):**
```java
HttpClient httpClient = HttpClient.newBuilder()
    .connectTimeout(connectTimeout)
    .executor(Executors.newVirtualThreadPerTaskExecutor())
    .version(HttpClient.Version.HTTP_2)
    .build();
```

**Problems:**
- JDK HttpClient has basic connection pooling (not configurable)
- No control over:
  - Maximum connections per host
  - Idle connection timeout
  - Connection lifetime
  - Pending acquire timeout
- HTTP/2 multiplexing helps but doesn't address pool management

**WebFlux Configuration (`WebClientConfig.java`):**
```java
ConnectionProvider connectionProvider = ConnectionProvider.builder("authorization-service-pool")
    .maxConnections(500)                    // ⚠️ 500 vs VT's unknown limit
    .pendingAcquireTimeout(Duration.ofSeconds(45))
    .maxIdleTime(Duration.ofSeconds(20))
    .maxLifeTime(Duration.ofSeconds(60))
    .evictInBackground(Duration.ofSeconds(120))
    .build();
```

**Advantages:**
- Explicit connection pool with 500 max connections
- Connections recycled after 60s (prevents stale connections)
- Idle connections closed after 20s (resource efficiency)
- Background eviction prevents connection leaks
- `pendingAcquireTimeout` provides clear failure mode under load

---

### 7. **Database Connection Pool Configuration**

**VT (HikariCP):**
```yaml
hikari:
  minimum-idle: 10
  maximum-pool-size: 80
  connection-timeout: 3000
```

**WF (R2DBC):**
```yaml
r2dbc:
  pool:
    initial-size: 10
    max-size: 80
    max-idle-time: 30m
    max-acquire-time: 10s
    max-create-connection-time: 10s
    validation-query: SELECT 1
```

**Analysis:**
- Both have similar pool sizes (80 max)
- VT uses blocking JDBC, so threads wait for DB queries
- WF uses R2DBC, so no threads block on DB operations
- For this application, DB is not the primary bottleneck (Elasticsearch + authorization-service are)
- However, for event creation/listing endpoints, WF has an advantage

---

## Performance Characteristics with Sparse Permissions

### Scenario: User searches 10,000 results, has 1% permission rate (100 authorized events)

#### Virtual Threads Timeline:
```
1. Fetch batch 1 (20 events) from ES              : 50ms
2. Block on permission check for batch 1          : 200ms
3. Return 0-1 authorized events
4. Fetch batch 2 (20 events) from ES              : 50ms
5. Block on permission check for batch 2          : 200ms
6. Return 0-1 authorized events
   ...repeat 500 times...
Total time: ~125 seconds (500 batches × 250ms/batch)
```

**Key Issues:**
- Sequential processing means no overlap between batches
- Empty batches (99% of them) still cost 250ms each
- Cannot "cancel early" when desired result count is reached efficiently

#### WebFlux Timeline:
```
1. Start fetching batches 1-4 from ES (parallel)  : 50ms
2. Start permission checks 1-4 (parallel)         : 0ms (non-blocking)
3. Batch 2 completes first (200ms) → emit 0 events
4. Start batch 5 permission check immediately
5. Batch 1 completes (205ms) → emit 1 event
6. Start batch 6 permission check immediately
7. ...pipeline continuously processes 4 batches in parallel...
Total time: ~25 seconds (500 batches, 4 at a time, ~200ms avg)
```

**Speedup: 5x faster** due to parallelization

---

## Configuration Recommendations

### For Virtual Threads Implementation

#### 1. Increase Concurrency Limit
```yaml
search-service:
  batch-concurrency: 16  # Up from 4
  batch-size: 50         # Up from 20 (fewer larger batches)
```

**Rationale:**
- With sparse permissions, more parallelism helps
- Larger batches reduce per-batch overhead
- Virtual threads are cheap; use more of them

#### 2. Optimize RestClient Configuration
Add connection pool settings (requires custom HttpClient configuration):
```yaml
authorization-service:
  max-connections: 100      # Explicit connection limit
  connection-ttl: 60s       # Connection lifetime
  keep-alive: true
```

#### 3. Tune HikariCP for Virtual Threads
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 200   # Increase for virtual threads
      minimum-idle: 50
```

#### 4. Remove Manual Backpressure Logic
Replace synchronized backpressure with simpler unbounded approach:
```java
// Remove maxInFlight logic entirely for SSE
// Let SseEmitter and HTTP flow control handle backpressure
eventStream.forEach(event -> {
    emitter.send(SseEmitter.event().data(event));
});
```

**Why:** HTTP flow control is more efficient than application-level synchronization.

---

### For WebFlux Implementation (Already Well-Tuned)

Minor optimizations:

#### 1. Consider Adjusting Concurrency for Sparse Permissions
```java
// In SearchService.java:64
.flatMapSequential(batch -> checkPermissionsBatch(batch, userId), 8)  // Up from 4
```

#### 2. Add Circuit Breaker for Authorization Service
```java
@Bean
public Retry retrySpec() {
    return Retry.backoff(3, Duration.ofMillis(100));
}

// In AuthorizationServiceClient:
.retryWhen(retrySpec())
.transform(CircuitBreakerOperator.of(circuitBreaker))
```

---

## Architectural Design Differences

### Virtual Threads: Imperative + Blocking
```
[Controller]
    ↓ (virtual thread blocks)
[Service: Stream.generate()]
    ↓ (virtual thread blocks on Future.get())
[ExecutorService: permission check]
    ↓ (virtual thread blocks on RestClient)
[Authorization Service]
```

**Characteristics:**
- Each request occupies a virtual thread for its entire lifetime
- Blocking calls "park" virtual threads (low memory cost)
- Easy to understand, imperative code style
- Performance depends on virtual thread scheduler efficiency

### WebFlux: Reactive + Non-Blocking
```
[Controller]
    ↓ (returns Flux immediately)
[Service: Flux pipeline]
    ↓ (subscription starts)
[Reactor: flatMapSequential]
    ↓ (concurrent subscriptions)
[Authorization Service via WebClient]
    ↓ (on Netty event loop)
[Results emitted via backpressure]
```

**Characteristics:**
- Request creates a subscription (no thread binding)
- Operations execute on shared Netty event loop threads
- Backpressure propagates through pipeline
- Higher throughput under load due to non-blocking I/O

---

## When Virtual Threads Could Match WebFlux Performance

Virtual threads work well when:
1. **Uniform permission distribution**: User has 50%+ permission rate
2. **Small result sets**: <100 total results needed
3. **Low authorization service latency**: <50ms per batch
4. **CPU-bound operations dominate**: More computation than I/O

Current scenario (sparse permissions + high I/O) favors WebFlux.

---

## Measurement & Profiling Recommendations

### Add Metrics to Both Implementations

```java
// Track batch processing time
Timer.Sample sample = Timer.start(meterRegistry);
// ... permission check ...
sample.stop(Timer.builder("search.permission.check")
    .tag("userId", userId)
    .tag("batchSize", String.valueOf(eventIds.size()))
    .register(meterRegistry));

// Track Elasticsearch query time
// Track authorization service call time
// Track end-to-end search latency
```

### Load Testing Script
```bash
# Test with sparse permissions (1% hit rate)
wrk -t4 -c100 -d60s --latency \
  -s search_test.lua \
  http://localhost:8083/api/rpc/v1/search/ndjson

wrk -t4 -c100 -d60s --latency \
  -s search_test.lua \
  http://localhost:8081/api/rpc/v1/search
```

---

## Specific Code Improvements

### VT: Replace Stream.generate() with CompletableFuture.allOf()

**Current (Sequential):**
```java
return Stream.generate(() -> {
    // FIFO queue processing
    return futureQueue.poll().get();
})
```

**Proposed (Parallel):**
```java
// Collect all futures up front
List<CompletableFuture<List<EventEntity>>> allFutures = new ArrayList<>();
while (iterator.hasNext()) {
    List<EventEntity> chunk = ...;
    allFutures.add(CompletableFuture.supplyAsync(() ->
        checkPermissionsBatch(chunk, userId), executorService));
}

// Wait for ALL futures to complete, return as stream
return CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0]))
    .thenApply(v -> allFutures.stream()
        .flatMap(f -> f.join().stream())
        .limit(limit))
    .join()
    .stream();
```

**Tradeoff:** Loses result ordering, but gains true parallelism.

---

### VT: Use Structured Concurrency (JEP 453)

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    // Submit all batch tasks
    List<Subtask<List<EventEntity>>> tasks = new ArrayList<>();
    while (iterator.hasNext()) {
        List<EventEntity> chunk = ...;
        tasks.add(scope.fork(() -> checkPermissionsBatch(chunk, userId)));
    }

    scope.join();           // Wait for all
    scope.throwIfFailed();  // Propagate errors

    // Collect results
    return tasks.stream()
        .flatMap(task -> task.get().stream())
        .limit(limit);
}
```

**Benefits:**
- Cleaner than manual Future management
- Automatic cancellation on failure
- Better error propagation

---

## Summary of Findings

| Aspect | Virtual Threads | WebFlux | Winner |
|--------|----------------|---------|--------|
| **Permission check parallelism** | Limited to 4, sequential | Up to 4, truly parallel | WebFlux |
| **Elasticsearch integration** | Blocking Stream API | Reactive Flux API | WebFlux |
| **HTTP client efficiency** | Basic JDK HttpClient | Reactor Netty with pooling | WebFlux |
| **Backpressure handling** | Manual synchronized blocks | Built-in Reactor operators | WebFlux |
| **Code complexity** | Lower (imperative style) | Higher (reactive style) | VT |
| **Thread utilization** | 1 virtual thread per request | Shared Netty event loop | WebFlux |
| **Sparse permission handling** | Poor (sequential batches) | Excellent (parallel pipeline) | WebFlux |
| **Dense permission handling** | Good | Excellent | WebFlux |
| **Resource efficiency** | Moderate | High | WebFlux |

---

## Recommendations Priority

### High Priority (Immediate Impact)
1. ✅ **Increase VT batch concurrency from 4 to 16+**
2. ✅ **Remove synchronized backpressure logic in SSE controller**
3. ✅ **Make batch size configurable (increase from 20 to 50+)**

### Medium Priority (Significant Impact)
4. ✅ **Replace Stream.generate() with CompletableFuture.allOf() or Structured Concurrency**
5. ✅ **Configure explicit connection limits for RestClient**
6. ✅ **Add performance metrics (Micrometer) to identify actual bottlenecks**

### Low Priority (Incremental Gains)
7. 📊 **Profile with JFR to find hidden hotspots**
8. 📊 **Consider hybrid approach: VT for controller, reactive for service layer**
9. 📊 **Benchmark different batch sizes (10, 20, 50, 100, 200)**

---

## Conclusion

The WebFlux implementation is fundamentally better suited for this workload due to:
- **True non-blocking I/O throughout the stack**
- **Natural parallelization via reactive operators**
- **Efficient backpressure without manual synchronization**
- **Superior HTTP client connection management**

The Virtual Threads implementation suffers from:
- **Sequential batch processing bottleneck** (primary issue)
- **Blocking Stream API forcing synchronous iteration**
- **Manual concurrency management with overhead**
- **Artificial concurrency limits** (CONCURRENCY=4)

For scenarios with sparse permissions (the stated problem), WebFlux can be **5-10x faster** due to parallel batch processing.

To make VT competitive, fundamental architectural changes are needed (move from Sequential Stream to parallel CompletableFuture/StructuredConcurrency), which would increase code complexity and reduce VT's main advantage (simplicity).
