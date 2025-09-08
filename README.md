# WebFlux SSE Event Streaming Application

A Spring WebFlux application that streams events from PostgreSQL database using Server-Sent Events (SSE) to a React frontend.

## Features

- **Real-time Event Streaming**: Server-Sent Events (SSE) API endpoint that streams database events
- **Event Creation**: POST API to create new events that appear immediately in the stream
- **PostgreSQL Integration**: Reactive R2DBC connection to PostgreSQL database
- **React Frontend**: Single-page application with event table and creation form
- **Auto-refresh**: Events automatically update every 2 seconds
- **Real-time Updates**: Newly created events appear immediately for all connected clients
- **Responsive Design**: Clean, modern UI that works on different screen sizes

## Prerequisites

- Java 17 or higher
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
1. Start PostgreSQL database in Docker
2. Wait for database to be ready
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

- `GET /` - Serves the React event stream dashboard
- `GET /create.html` - Serves the React event creation form
- `GET /api/events` - Returns all events as JSON (ordered by timestamp DESC)
- `GET /api/events/stream` - SSE endpoint that streams events every 2 seconds
- `POST /api/events` - Creates a new event (returns 201 Created with event data)

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

## Configuration

Database connection settings can be modified in `src/main/resources/application.yml`:

```yaml
spring:
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/eventdb
    username: postgres
    password: password
```

## Architecture

- **Backend**: Spring Boot with WebFlux (reactive web framework)
- **Database**: PostgreSQL with R2DBC (reactive database connectivity)
- **Frontend**: React (loaded via CDN for simplicity)
- **Communication**: Server-Sent Events for real-time updates
- **Testing**: Testcontainers with PostgreSQL for integration tests

## Testing Strategy

- **Unit Tests**: Fast, isolated tests using Surefire plugin
- **Integration Tests**: End-to-end tests using Failsafe plugin with Testcontainers PostgreSQL
- **Database Testing**: Real PostgreSQL database in Docker containers
- **API Testing**: WebTestClient for reactive web layer testing
- **SSE Testing**: StepVerifier for reactive stream testing

## Event Model

Each event contains:
- `id`: Unique identifier
- `timestamp`: When the event occurred
- `title`: Short event title
- `description`: Detailed event description