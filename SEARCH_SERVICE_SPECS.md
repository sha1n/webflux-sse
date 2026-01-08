# Search Service Specifications

This document provides the functional and non-functional specifications for an events service that provides basic
create/get and search capabilities with authorization checks.

* API models can be reviewed in the [search API module](./backend/search/search-service-api)

## Functional Specifications

### Event Search API

- The search service implements an SSE (server Sent Events) based search API.
- The maximum number of search results returned is capped at 200.
- The requesting user must be authorized to view each search result.
- The search service calls the authorization service to authorize each search result.
- If results are dropped due to authorization, more results are fetched until either the maximum number of results is
  reached or there are no more results to fetch.
- Search results must be returned in the order they are returned from the ElasticSearch server.

### Events Fetch API

- An SSE fetch API that returns the last 100 events to the dashboard UI page sorted by insertion time.

### Events Ingestion Bulk API

- The service needs to have an ingestion API that stores bulks of documents into an ElasticSearch index.

## Non-Functional Specifications

- The service must be as fast and efficient as possible to create the best user experience possible.
- The service must follow tech and design best practices
    - Proper layered design
    - Production grade config tuning:
        - Connection pool sizes
        - Thread pool sizes
        - Proper batching and buffering to improve performance and reduce load on downstream services
- The service must be resilient to downstream service failures
 