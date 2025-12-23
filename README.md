# WebFlux SSE Event Streaming Application

A Spring WebFlux application demonstrating real-time event streaming using Server-Sent Events (SSE) with a reactive architecture. Features dual persistence (PostgreSQL + Elasticsearch), permission-based access control, and full-text search capabilities with a React frontend.

## Features

### Core Functionality
- **Real-time Event Streaming**: SSE API endpoint that streams database events every 2 seconds with deduplication
- **Event Creation**: POST API to create new events that appear immediately in the stream
- **Event Retrieval**: GET endpoint returning all events ordered by timestamp DESC
- **Dual Persistence**: Events are stored in both PostgreSQL (primary) and Elasticsearch (search index)
- **Responsive Design**: Clean, modern UI that works on different screen sizes

### Advanced Features
- **Permission-Based Access Control**: Fine-grained user-event permission system
  - Grant/revoke permissions to specific events
  - Bulk permission assignment
  - Permission filtering on search results
- **Full-Text Search**: Elasticsearch-powered search with permission-aware filtering
  - Case-insensitive search across event titles and descriptions
  - NDJSON streaming format for efficient data transfer
  - User-specific result filtering
- **Reactive Architecture**: Non-blocking, backpressure-aware streams throughout
- **Graceful Degradation**: Application works with PostgreSQL only if Elasticsearch is unavailable

## Prerequisites

- Java 21
- Maven 3.6+
- Docker and Docker Compose

## Quick Start

The easiest way to run the complete application with database:

```bash
# Start everything (database + application)
./start.sh

# Stop everything
./stop.sh

# Run integration tests
./test.sh
```

The start script will:
1. Start PostgreSQL and Elasticsearch in Docker
2. Wait for services to be ready
3. Start the Spring Boot application
4. Display connection information

## Manual Setup

If you prefer to run components separately:

### 1. Start Database Only
```bash
docker-compose up -d
```

### 2. Start Application Only
```bash
mvn spring-boot:run
```

### 3. Access Application
Open your browser and navigate to: `http://localhost:8080`

### 4. Run Tests
```bash
# Unit tests (Surefire)
mvn test

# Integration tests with Testcontainers PostgreSQL (Failsafe)
mvn verify
```

## API Endpoints

### Frontend Pages
- `GET /` - React event stream dashboard
- `GET /create.html` - React event creation form
- `GET /search.html` - Search interface
- `GET /permissions.html` - Permission management interface

### Event Endpoints
- `GET /api/events` - Returns all events as JSON (ordered by timestamp DESC)
- `GET /api/events/stream` - SSE endpoint that streams events every 2 seconds (Content-Type: text/event-stream)
- `POST /api/events` - Creates a new event (returns 201 Created with event data)

### Search Endpoints
- `GET /api/search` - Full-text search with permission filtering (Content-Type: application/x-ndjson)
  - Query parameter: `q` - search query string
  - Header: `X-User-Id` - user identifier for permission filtering
  - Returns: NDJSON stream of matching events the user has permission to view

### Permission Endpoints
- `GET /api/permissions` - Get all permissions
- `POST /api/permissions` - Grant permission to a user for an event
- `DELETE /api/permissions/event/{eventId}/user/{userId}` - Revoke permission
- `POST /api/permissions/bulk` - Bulk grant permissions to a user for multiple events

### POST /api/events

Creates a new event and immediately persists it to the database.

**Request Body:**
```json
{
  "title": "Event Title",
  "description": "Event Description (optional)"
}
```

**Response:** `201 Created`
```json
{
  "id": 123,
  "timestamp": "2025-09-08T14:30:45.123",
  "title": "Event Title", 
  "description": "Event Description"
}
```

**Features:**
- Automatically sets timestamp to current time
- Generates unique ID via database sequence
- Events appear immediately in SSE stream
- Events are immediately available via GET endpoint

### Example with cURL

Create a new event using cURL:

```bash
# Create an event with title and description
curl -X POST http://localhost:8080/api/events \
  -H "Content-Type: application/json" \
  -d '{
    "title": "System Deployment",
    "description": "Application deployed to production environment"
  }'

# Create an event with title only
curl -X POST http://localhost:8080/api/events \
  -H "Content-Type: application/json" \
  -d '{
    "title": "User Login",
    "description": ""
  }'

# Response example:
{
  "id": 42,
  "timestamp": "2025-09-08T14:30:45.123456",
  "title": "System Deployment",
  "description": "Application deployed to production environment"
}
```

The event will appear immediately in:
- SSE stream at `/api/events/stream`
- GET endpoint at `/api/events`
- React dashboard at `http://localhost:8080`
- Elasticsearch search index

### POST /api/permissions

Grant permission to a user for a specific event:

```bash
curl -X POST http://localhost:8080/api/permissions \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": 42,
    "userId": "user123"
  }'
```

### POST /api/permissions/bulk

Grant permissions to a user for multiple events:

```bash
curl -X POST http://localhost:8080/api/permissions/bulk \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user123",
    "eventIds": [1, 2, 3, 4, 5]
  }'
```

### GET /api/search

Search for events with permission filtering:

```bash
# Search for events containing "deployment"
curl -X GET "http://localhost:8080/api/search?q=deployment" \
  -H "X-User-Id: user123"

# Response (NDJSON format):
{"id":42,"timestamp":"2025-09-08T14:30:45.123456","title":"System Deployment","description":"Application deployed to production"}
{"id":43,"timestamp":"2025-09-08T15:20:10.654321","title":"Database Deployment","description":"Schema updates deployed"}
```

### DELETE /api/permissions/event/{eventId}/user/{userId}

Revoke permission from a user:

```bash
curl -X DELETE http://localhost:8080/api/permissions/event/42/user/user123
```

## Configuration

Database and Elasticsearch connection settings can be modified in `src/main/resources/application.yml`:

```yaml
spring:
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/eventdb
    username: postgres
    password: password
  elasticsearch:
    uris: http://localhost:9200
```

Note: Elasticsearch is optional. If not configured, the application will work with PostgreSQL only and search functionality will be disabled.

## Architecture

- **Backend**: Spring Boot 3.2.0 with WebFlux (reactive web framework)
- **Database**:
  - PostgreSQL with R2DBC (reactive database connectivity)
  - Elasticsearch 8.8.0 for full-text search
- **Frontend**: React 18 (loaded via CDN for simplicity)
- **Communication**:
  - Server-Sent Events (SSE) for real-time updates
  - NDJSON streaming for search results
- **Testing**: Testcontainers 2.0.2 with PostgreSQL and Elasticsearch for integration tests
- **Reactive Streams**: Project Reactor for backpressure-aware streaming

## Testing Strategy

- **Unit Tests**: Fast, isolated tests using Surefire plugin
- **Integration Tests**: End-to-end tests using Failsafe plugin with Testcontainers
  - `EventControllerIT` - Event CRUD and SSE streaming tests
  - `PermissionControllerIT` - Permission management API tests
  - `StreamingSearchIT` - Search functionality tests
  - `DualPersistenceIT` - Dual PostgreSQL/Elasticsearch persistence tests
  - `SearchPocIntegrationIT` - Search proof-of-concept tests
- **Database Testing**: Real PostgreSQL and Elasticsearch databases in Docker containers
- **API Testing**: WebTestClient for reactive web layer testing
- **SSE Testing**: StepVerifier for reactive stream testing
- **Dynamic Configuration**: DynamicPropertyRegistry for runtime test configuration

## Data Models

### Event
Each event contains:
- `id`: Unique identifier (BIGSERIAL, auto-generated)
- `timestamp`: When the event occurred (defaults to CURRENT_TIMESTAMP)
- `title`: Short event title (required, indexed for search)
- `description`: Detailed event description (optional, indexed for search)

Events are dual-persisted in both PostgreSQL (with `@Table` annotation) and Elasticsearch (with `@Document` annotation) for optimal querying and search performance.

### UserEventPermission
Permission model for access control:
- `id`: Unique identifier (auto-generated)
- `eventId`: Reference to event
- `userId`: String identifier for user
- Unique constraint on (eventId, userId) prevents duplicate permissions

## Technologies Used

- **Java 21** - Latest LTS version
- **Spring Boot 3.2.0** - Application framework
- **Spring WebFlux** - Reactive web framework
- **Spring Data R2DBC** - Reactive database access
- **Spring Data Elasticsearch** - Reactive search integration
- **Project Reactor** - Reactive streams implementation
- **PostgreSQL 15** - Primary database
- **Elasticsearch 8.8.0** - Search engine
- **React 18** - Frontend framework
- **Testcontainers 2.0.2** - Container-based testing
- **Maven** - Build tool

## Project Structure

```
src/
├── main/
│   ├── java/com/example/webfluxsse/
│   │   ├── controller/         # REST controllers
│   │   │   ├── EventController.java
│   │   │   ├── SearchController.java
│   │   │   └── PermissionController.java
│   │   ├── model/              # Domain models
│   │   │   ├── Event.java
│   │   │   └── UserEventPermission.java
│   │   ├── repository/         # Data access
│   │   │   ├── r2dbc/          # PostgreSQL repositories
│   │   │   └── elasticsearch/  # Elasticsearch repositories
│   │   ├── service/            # Business logic
│   │   │   ├── EventsService.java
│   │   │   └── SearchService.java
│   │   ├── config/             # Configuration
│   │   └── WebfluxSseApplication.java
│   └── resources/
│       ├── application.yml
│       ├── schema.sql
│       ├── init.sql
│       ├── data.sql
│       └── static/             # Frontend files
│           ├── index.html
│           ├── create.html
│           ├── search.html
│           ├── permissions.html
│           ├── css/
│           └── js/
└── test/                       # Integration tests
```