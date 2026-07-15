# Ticketmaster Clone

A backend for the classic "design a ticket booking system" problem — see
[`docs/design.md`](docs/design.md) for the full write-up, and
[`docs/adr/`](docs/adr) for the reasoning behind key technical decisions.

## Stack

- Java 21, Spring Boot 4.1.0 (Spring Framework 7)
- Spring Data JPA / Hibernate
- PostgreSQL (see [ADR-0002](docs/adr/0002-database-choice.md))
- Redis — waiting-room queue and admission tokens (`queue/`), via
  `StringRedisTemplate` and Lua scripts (`src/main/resources/scripts/`) for
  the operations that need to be atomic
- Maven (via `./mvnw`)
- JaCoCo for test coverage reporting

## Prerequisites

- Java 21+
- A local PostgreSQL instance reachable at `localhost:5432`. There's no
  in-memory/H2 fallback — tests run against a real database
  (`@DataJpaTest` + `@AutoConfigureTestDatabase(replace = Replace.NONE)`).

  The project uses three databases so that running the app, running the tests,
  and demoing never interfere with one another:

  ```bash
  createdb ticketmaster        # default profile: `./mvnw spring-boot:run`
  createdb ticketmaster_test   # test suite (activated via the `test` profile in pom.xml)
  createdb ticketmaster_dev    # demo (`dev` profile)
  ```

  Only `ticketmaster` is strictly required to run the app. `ticketmaster_test`
  is required to run the test suite; `ticketmaster_dev` only for the demo
  profile. Each is selected by a Spring profile:
  `src/main/resources/application.properties` (default),
  `src/test/resources/application-test.properties`,
  `src/main/resources/application-dev.properties`.

  By default the app connects as `$USER` with no password
  (`spring.datasource.username=${USER}`). Adjust
  `src/main/resources/application.properties` if your local Postgres setup
  differs.

- A local Redis instance reachable at `localhost:6379` (no auth). Used for
  the queue package's waiting-room state and access tokens.

## Running

```bash
./mvnw spring-boot:run
```

The app starts on the default port (8080), against the `ticketmaster` database.

For a hands-on demo with the waiting room made visible (low admit rate) and
against the isolated `ticketmaster_dev` database:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
psql -d ticketmaster_dev -f scripts/seed-dev.sql   # optional demo data
```

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
├── common/    # cross-cutting infrastructure (ApiExceptionHandler, RedisScriptConfig)
├── event/     # event lifecycle: on-sale/sold-out sweeps, cancellation cascade
├── venue/
├── seat/
├── ticket/
├── booking/   # hold/confirm/expire/cancel, queue-gating, per-user ticket cap
├── payment/   # pay/refund
├── queue/     # Redis-backed waiting-room queue and admission tokens
└── user/
```

Each feature package generally follows the same layering: `Entity`,
`Repository` (Spring Data JPA), `Service` (business rules), `Controller`
(Spring MVC). `queue/` has no entity/repository — its state lives entirely
in Redis.

## API

| Method | Path                          | Description                                          |
|--------|-------------------------------|-------------------------------------------------------|
| GET    | `/events`                     | List events; optional `name`/`status` filters          |
| GET    | `/events/{id}`                | Get an event by id                                     |
| POST   | `/events/{id}/cancel`         | Cancel an event (cascades to bookings/refunds/tickets) |
| GET    | `/venues`                     | List venues                                            |
| GET    | `/venues/{id}`                | Get a venue by id                                      |
| GET    | `/venues/{venueId}/seats`     | List seats for a venue                                 |
| GET    | `/seats/{id}`                 | Get a seat by id                                       |
| GET    | `/events/{eventId}/tickets`   | List tickets for an event; optional `status` filter    |
| GET    | `/tickets/{id}`               | Get a ticket by id                                     |
| POST   | `/bookings/hold`               | Hold tickets (idempotent; may require a queue access token) |
| POST   | `/bookings/{id}/pay`          | Confirm a held booking with payment                    |
| POST   | `/bookings/{id}/cancel`       | Cancel a booking                                       |
| GET    | `/bookings/{id}`              | Get a booking by id                                    |
| GET    | `/users/{userId}/bookings`    | List bookings for a user                               |
| POST   | `/bookings/{id}/refund`       | Refund a confirmed booking's payment                   |
| GET    | `/bookings/{id}/payments`     | List payments for a booking                            |
| GET    | `/payments/{id}`              | Get a payment by id                                    |
| POST   | `/events/{id}/queue`          | Join the waiting-room queue for an event; returns a token |
| GET    | `/events/{eventId}/queue/{token}` | Poll queue status/position for a token             |

All not-found lookups return `404` via the centralized
`ApiExceptionHandler` (`common/`).
