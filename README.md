# Ticketmaster Clone

A backend for the classic "design a ticket booking system" problem — see
[`docs/design.md`](docs/design.md) for the full write-up, and
[`docs/adr/`](docs/adr) for the reasoning behind key technical decisions.

## Stack

- Java 21, Spring Boot 4.1.0 (Spring Framework 7)
- Spring Data JPA / Hibernate
- PostgreSQL (see [ADR-0002](docs/adr/0002-database-choice.md))
- Maven (via `./mvnw`)
- JaCoCo for test coverage reporting

## Prerequisites

- Java 21+
- A local PostgreSQL instance with a `ticketmaster` database, reachable at
  `localhost:5432`. Tests and the app both connect to this real database
  (see `src/main/resources/application.properties` and `@DataJpaTest` +
  `@AutoConfigureTestDatabase(replace = Replace.NONE)` in the test suite) —
  there's no in-memory/H2 fallback.

  ```bash
  createdb ticketmaster
  ```

  By default the app connects as `$USER` with no password
  (`spring.datasource.username=${USER}`). Adjust
  `src/main/resources/application.properties` if your local Postgres setup
  differs.

## Running

```bash
./mvnw spring-boot:run
```

The app starts on the default port (8080).

## Testing

```bash
./mvnw test          # run the test suite
./mvnw clean verify  # run tests + generate JaCoCo coverage report
```

Coverage report: `target/site/jacoco/index.html` after `verify`.

## Project Structure

Package-by-feature, one package per domain concept:

```
com.ticketmaster/
├── common/    # cross-cutting infrastructure (e.g. ApiExceptionHandler)
├── event/
├── venue/
├── seat/
├── ticket/
├── booking/   # in progress
├── payment/   # not yet started
└── user/      # not yet started
```

Each feature package follows the same layering: `Entity`, `Repository`
(Spring Data JPA), `Controller` (Spring MVC).

## API

| Method | Path                      | Description                                  |
|--------|---------------------------|-----------------------------------------------|
| GET    | `/events`                 | List events; optional `name`/`status` filters |
| GET    | `/events/{id}`            | Get an event by id                            |
| GET    | `/venues`                 | List venues                                   |
| GET    | `/venues/{id}`            | Get a venue by id                             |
| GET    | `/venues/{venueId}/seats` | List seats for a venue                        |
| GET    | `/seats/{id}`             | Get a seat by id                              |
| GET    | `/events/{eventId}/tickets` | List tickets for an event; optional `status` filter |
| GET    | `/tickets/{id}`           | Get a ticket by id                            |

All not-found lookups return `404` via the centralized
`ApiExceptionHandler` (`common/`).
