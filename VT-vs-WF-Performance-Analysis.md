# Performance Analysis: Virtual Threads (VT) vs WebFlux (WF) Search Services

## Executive Summary

The Virtual Threads implementation performs significantly worse than the WebFlux version due to **fundamental
architectural differences** that prevent true streaming and introduce blocking operations throughout the stack. The
critical issue is that the VT version **materializes the entire result set into memory** before processing, defeating
the purpose of streaming.

---

## Architecture Comparison

### Stack Differences

| Component             | VT Version                         | WF Version                             |
|-----------------------|------------------------------------|----------------------------------------|
| **Web Framework**     | Spring MVC (blocking)              | Spring WebFlux (non-blocking)          |
| **Database Layer**    | JPA/Hibernate + JDBC               | R2DBC                                  |
| **Connection Pool**   | HikariCP (blocking)                | R2DBC Pool (non-blocking)              |
| **HTTP Client**       | RestClient (blocking)              | WebClient (non-blocking)               |
| **Elasticsearch**     | ElasticsearchRepository (Stream)   | ReactiveElasticsearchRepository (Flux) |
| **Concurrency Model** | Platform threads + Virtual threads | Event loop (Netty)                     |
| **Streaming**         | Java Stream → SseEmitter           | Flux → Native SSE                      |

---

## Critical Performance Bottlenecks in VT Version

### 1. **SEVERE: Full Materialization of Results** (Most Critical)

**Location:** `SearchService.batchedPermissionFilter()` - Line 75

```java
private Stream<EventEntity> batchedPermissionFilter(Stream<EventEntity> stream, String userId, int batchSize) {
    List<EventEntity> allEntities = stream.collect(Collectors.toList());  // ⚠️ BLOCKS AND LOADS ALL RESULTS

    return partitionList(allEntities, batchSize).stream()
            .flatMap(batch -> checkPermissionsBatch(batch, userId).stream())
            .sequential();
}
```

**Impact:**

- **Memory**: Loads thousands/millions of events into heap before processing
- **Latency**: First result can't be sent until ALL Elasticsearch results are fetched
- **Throughput**: No streaming - defeats the entire purpose
- **Backpressure**: Cannot apply flow control

**WebFlux Equivalent** (Processes incrementally):

```java
return searchResults
        .bufferTimeout(20,Duration.ofSeconds(5))  // ✓ Incremental batching
        .

flatMap(batch ->

checkPermissionsBatch(batch, userId))
        .

flatMapIterable(Function.identity())
```

### 2. **Blocking I/O in Authorization Client**

**Location:** `AuthorizationServiceClient` - Lines 36-44

```java
BatchPermissionCheckResponse response = restClient.post()
        .uri("/api/v1/permissions/batch-check")
        .body(request)
        .retrieve()
        .toEntity(BatchPermissionCheckResponse.class)
        .getBody();  // ⚠️ BLOCKS thread waiting for HTTP response
```

**Impact:**

- Blocks virtual thread for each permission check
- Even on virtual threads, blocking is less efficient than reactive
- No connection pooling configuration visible for RestClient
- No timeout enforcement in the call itself

**WebFlux Equivalent** (Non-blocking):

```java
return webClient.post()
    .

uri("/api/v1/permissions/batch-check")
    .

bodyValue(request)
    .

retrieve()
    .

bodyToMono(BatchPermissionCheckResponse .class)
    .

timeout(timeout)  // ✓ Reactive timeout
```

### 3. **Blocking Database Access with JPA**

**Location:** Throughout - JPA/Hibernate with JDBC

**Impact:**

- All database queries block threads
- Connection pool exhaustion under high load
- HikariCP configured with 80 max connections - finite resource
- Query execution holds thread until results complete

**WebFlux Equivalent:**

- R2DBC - Non-blocking database driver
- Connection pool doesn't block threads
- Better resource utilization

### 4. **Synchronous Elasticsearch Queries**

**Location:** `EventElasticsearchRepository` returns `Stream<EventEntity>`

```java
Stream<EventEntity> searchByTitleOrDescription(String query);
```

**Impact:**

- Blocks until Elasticsearch query completes
- Returns Java Stream (pull-based, not reactive)
- No backpressure mechanism

**WebFlux Equivalent:**

```java
Flux<EventEntity> searchByTitleOrDescription(String query);
```

- Reactive Elasticsearch client
- True streaming with backpressure
- Results pushed as they arrive

### 5. **Inefficient SSE Implementation**

**Location:** `SearchController.createSseEmitter()` - Lines 64-72

```java
sseExecutor.execute(() ->{
        try(
var eventStream = searchService.searchEventsForUser(query, userId, limit)){
        eventStream.

forEach(event ->{
        try{
        emitter.

send(SseEmitter.event().

data(event));
        }catch(
IOException e){
        throw new

RuntimeException("Error sending event",e);
            }
                    });
                    emitter.

complete();
    }
            });
```

**Issues:**

- Manual executor management (`newVirtualThreadPerTaskExecutor()`)
- Exception handling wraps IOException in RuntimeException
- Stream is already materialized by the time it reaches here
- No backpressure - if client slow, buffer grows

**WebFlux Equivalent:**

- Framework handles SSE natively with Flux
- Automatic backpressure
- No manual executor needed
- Clean error handling

---

## Configuration Analysis

### Connection Pools

**VT - HikariCP:**

```yaml
hikari:
  minimum-idle: 10
  maximum-pool-size: 80
  connection-timeout: 3000
  idle-timeout: 600000
  max-lifetime: 1800000
```

**WF - R2DBC:**

```yaml
pool:
  initial-size: 10
  max-size: 80
  max-idle-time: 30m
  max-acquire-time: 10s
  max-create-connection-time: 10s
  validation-query: SELECT 1
```

**Analysis:**

- Similar pool sizes (80 max)
- VT pool is blocking - threads wait for connections
- WF pool is non-blocking - async acquisition
- VT: 80 max connections × 80 threads = potential for deadlock/starvation
- WF: 80 connections shared across event loop (fewer threads)

### Server Configuration

Both use default server configurations, but:

- **VT**: Tomcat (default 200 threads max) + virtual threads for SSE
- **WF**: Netty (event loop, typically 2×CPU cores)

---

## Optimization Recommendations for VT Version

### Priority 1: Fix Stream Materialization (CRITICAL)

**Current Problem:**

```java
List<EventEntity> allEntities = stream.collect(Collectors.toList());
```

**Solution Options:**

**Option A: Streaming Batching (Best for VT)**

```java
private Stream<EventEntity> batchedPermissionFilter(Stream<EventEntity> stream, String userId, int batchSize) {
    Iterator<EventEntity> iterator = stream.iterator();

    return Stream.generate(() -> {
                List<EventEntity> batch = new ArrayList<>(batchSize);
                while (iterator.hasNext() && batch.size() < batchSize) {
                    batch.add(iterator.next());
                }
                return batch;
            })
            .takeWhile(batch -> !batch.isEmpty())
            .flatMap(batch -> checkPermissionsBatch(batch, userId).stream());
}
```

**Option B: Limit Before Processing**

```java
private Stream<EventEntity> batchedPermissionFilter(Stream<EventEntity> stream, String userId, int batchSize, int limit) {
    List<EventEntity> limitedEntities = stream
            .limit(limit * 2)  // Fetch 2x limit to account for filtering
            .collect(Collectors.toList());
    // ... rest of logic
}
```

### Priority 2: Configure RestClient with Connection Pool

**Add Configuration:**

```java

@Bean
public RestClient restClient(@Value("${authorization-service.base-url}") String baseUrl) {
    return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .executor(Executors.newVirtualThreadPerTaskExecutor())
                            .build()
            ))
            .build();
}
```

### Priority 3: Add Response Timeouts

**In AuthorizationServiceClient:**

```java
// Add explicit timeout support
private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

public BatchPermissionCheckResponse checkBatchPermissions(List<Long> eventIds, String userId) {
    CompletableFuture<ResponseEntity<BatchPermissionCheckResponse>> future =
            CompletableFuture.supplyAsync(() ->
                            restClient.post()
                                    .uri("/api/v1/permissions/batch-check")
                                    .body(request)
                                    .retrieve()
                                    .toEntity(BatchPermissionCheckResponse.class),
                    virtualThreadExecutor
            );

    try {
        return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS).getBody();
    } catch (TimeoutException e) {
        // Handle timeout
    }
}
```

### Priority 4: Optimize Elasticsearch Client

**Check Elasticsearch client configuration:**

- Ensure connection pooling is configured
- Set appropriate timeouts
- Consider using async Elasticsearch client underneath

### Priority 5: Tune Thread Pools

**Add to application.yml:**

```yaml
server:
  tomcat:
    threads:
      max: 400  # Increase for virtual threads
      min-spare: 20
    max-connections: 10000
    accept-count: 200
```

### Priority 6: Add Streaming Limit Logic

**Implement early termination:**

```java
public Stream<Event> searchEventsForUser(String query, String userId, Integer limit) {
    int resultLimit = (limit != null && limit > 0) ? limit : 200;

    // Use limit on Elasticsearch query, not just on final stream
    AtomicInteger count = new AtomicInteger(0);

    return searchResults
            .takeWhile(e -> count.getAndIncrement() < resultLimit * 3)  // Over-fetch for filtering
            // ... permission filtering
            .limit(resultLimit);
}
```

### Priority 7: Implement Backpressure Mechanism

**Add flow control:**

```java
private SseEmitter createSseEmitter(String query, String userId, Integer limit) {
    SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

    sseExecutor.execute(() -> {
        try {
            AtomicInteger inFlight = new AtomicInteger(0);
            Object lock = new Object();

            try (var eventStream = searchService.searchEventsForUser(query, userId, limit)) {
                eventStream.forEach(event -> {
                    synchronized (lock) {
                        while (inFlight.get() > 10) {  // Limit in-flight events
                            try {
                                lock.wait(100);
                            } catch (InterruptedException e) {
                            }
                        }
                        inFlight.incrementAndGet();
                    }

                    try {
                        emitter.send(SseEmitter.event().data(event));
                        synchronized (lock) {
                            inFlight.decrementAndGet();
                            lock.notify();
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    });
    return emitter;
}
```

---

## Performance Impact Estimates

| Issue                  | Current Impact               | After Fix           | Improvement                  |
|------------------------|------------------------------|---------------------|------------------------------|
| Stream materialization | O(N) memory, full latency    | O(batchSize) memory | 10-100x memory, 50%+ latency |
| Blocking HTTP calls    | Thread per call              | Still blocking      | Minor (VT helps)             |
| Database blocking      | Connection pool contention   | Still blocking      | Minor (VT helps)             |
| No backpressure        | Memory bloat on slow clients | Controlled flow     | Prevents OOM                 |
| Elasticsearch blocking | Query latency blocks thread  | Still blocking      | Minimal                      |

**Key Insight:** Virtual threads help with blocking I/O but **cannot fix architectural issues** like materializing
entire streams into memory.

---

## Why WebFlux Still Wins

Even with all optimizations, WebFlux will outperform because:

1. **True non-blocking I/O**: No thread blocking anywhere
2. **Native backpressure**: Built into Reactor/Flux
3. **Event loop efficiency**: Fewer threads, more work
4. **Memory efficiency**: Streams never materialized
5. **Framework integration**: Everything designed for reactive

**Virtual threads are excellent for:**

- Migrating blocking code with minimal changes
- I/O-bound workloads with many blocking calls
- Simplifying async code with synchronous style

**Virtual threads are NOT a silver bullet for:**

- Replacing true streaming architectures
- Avoiding materialization of large datasets
- Achieving maximum throughput with backpressure

---

## Recommended Action Plan

1. **Immediate** (Hours): Fix stream materialization - implement streaming batching
2. **Short-term** (Days): Add proper timeouts, connection pooling, thread pool tuning
3. **Medium-term** (Weeks): Consider hybrid approach - keep VT for simplicity, but use reactive clients (WebClient) for
   I/O
4. **Long-term** (Months): Evaluate if virtual threads is the right model for this use case vs full reactive

The VT version **can** be made performant, but it will never match WebFlux for streaming workloads without fundamentally
changing the architecture.

---

## File Locations Reference

### VT Service (Port 8083)

- Main service:
  `backend/search/search-server-vt/src/main/java/com/example/virtualthreads/search/service/SearchService.java`
- Controller:
  `backend/search/search-server-vt/src/main/java/com/example/virtualthreads/search/controller/SearchController.java`
- Auth client:
  `backend/search/search-server-vt/src/main/java/com/example/virtualthreads/search/client/AuthorizationServiceClient.java`
- Config: `backend/search/search-server-vt/src/main/resources/application.yml`

### WF Service (Port 8081)

- Main service: `backend/search/search-server-wf/src/main/java/com/example/webfluxsse/search/service/SearchService.java`
- Controller:
  `backend/search/search-server-wf/src/main/java/com/example/webfluxsse/search/controller/SearchController.java`
- Auth client:
  `backend/search/search-server-wf/src/main/java/com/example/webfluxsse/search/client/AuthorizationServiceClient.java`
- Config: `backend/search/search-server-wf/src/main/resources/application.yml`
