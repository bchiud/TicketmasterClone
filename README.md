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
psql -d ticketmaster_dev -f scripts/seed-dev.sql   # optional demo data (idempotent; re-runnable)
```

[`docs/demo-runbook.md`](docs/demo-runbook.md) is a full copy-paste walkthrough over `curl`:
browse/search, booking an open event, the waiting room (escape hatch, backlog drain, rate
limiting), and admin teardown.

A `prod` profile (`src/main/resources/application-prod.properties`) is also available for
deployment behind a trusted reverse proxy — it enables `X-Forwarded-For` handling so the per-IP
enqueue rate limiter sees real client IPs rather than the proxy's.

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
| GET    | `/events`                     | List/search events; optional `name`, `status`, `city`, `performer`, `from`, `to` filters (all optional, ANDed) |
| GET    | `/events/{id}`                | Get an event by id                                     |
| POST   | `/events`                     | Create an event; fans out one ticket per venue seat (`201`) |
| POST   | `/events/{id}/cancel`         | Cancel an event (cascades to bookings/refunds/tickets, purges queue state) |
| GET    | `/venues`                     | List venues                                            |
| GET    | `/venues/{id}`                | Get a venue by id                                      |
| POST   | `/venues`                     | Create a venue (`201`)                                 |
| GET    | `/venues/{venueId}/seats`     | List seats for a venue                                 |
| GET    | `/seats/{id}`                 | Get a seat by id                                       |
| GET    | `/events/{eventId}/tickets`   | List tickets for an event; optional `status` filter    |
| GET    | `/tickets/{id}`               | Get a ticket by id                                     |
| POST   | `/bookings/hold`               | Hold tickets (idempotent; may require a queue access token) |
| POST   | `/bookings/{id}/pay`          | Confirm a held booking with payment (idempotent — a retry on a confirmed booking returns it, no double-charge) |
| POST   | `/bookings/{id}/cancel`       | Cancel a booking                                       |
| GET    | `/bookings/{id}`              | Get a booking by id                                    |
| GET    | `/users/{userId}/bookings`    | List bookings for a user                               |
| POST   | `/bookings/{id}/refund`       | Refund a confirmed booking's payment                   |
| GET    | `/bookings/{id}/payments`     | List payments for a booking                            |
| GET    | `/payments/{id}`              | Get a payment by id                                    |
| POST   | `/events/{id}/queue`          | Join the waiting-room queue; returns a token (per-IP rate limited → `429`) |
| GET    | `/events/{eventId}/queue/{token}` | Poll queue status/position for a token             |

Errors are mapped centrally by `ApiExceptionHandler` (`common/`):
`400` (invalid request body, or creating an event against a seatless venue),
`403` (booking a queue-gated event without a valid access token),
`404` (unknown id), `409` (state-machine conflicts like paying a cancelled booking, or a concurrent-modification conflict from optimistic/row locking),
`429` (queue join over the per-IP rate limit).

## Known limitations & next steps

A few things are deliberately scoped out to keep the project focused on its core — the
concurrency-safe booking path — rather than production-hardened across the board. Each is a
conscious trade-off, not an oversight:

- **Schedulers assume a single instance.** The three `@Scheduled` sweeps — queue admission
  (`QueueService.admit`), booking expiry (`BookingService.expire`), and on-sale activation
  (`EventService.activateOnSaleEvents`) — run on every node, so deploying N replicas would run
  each sweep N times. It's currently safe-ish, since each has an independent guard:
  - queue admission pops via atomic Lua `ZPOPMIN`, so instances split the work rather than
    double-admit;
  - expiry mutates each booking under its `@Version` optimistic lock, so a concurrent second
    sweep loses cleanly;
  - on-sale activation is idempotent (`SCHEDULED → ON_SALE` twice is harmless).
  - *Next:* a distributed lock (ShedLock's `@SchedulerLock`, backed by the existing Redis) or
    leader election, so each sweep runs once cluster-wide instead of relying on per-operation
    guards.

- **No authentication or authorization.** `userId` comes from the request body/path, and by-id
  endpoints have no ownership checks — any caller can read or mutate any booking. Deliberately
  omitted so the demo stays on the booking/concurrency core.
  - *Next:* authenticate at the gateway, derive `userId` from the authenticated principal (not
    the request), and enforce ownership on every by-id read and mutation.

- **Schema is managed by Hibernate `ddl-auto=update`, not migrations.** Fine for a single-dev
  project, but it never drops/retypes columns and can't express everything — e.g. the partial
  unique index on `payments` lives in `schema.sql`, not the entity mapping.
  - *Next:* Flyway/Liquibase versioned migrations as the single source of schema truth.

- **The payment double-charge backstop surfaces as `500`, by design.** "At most one `SUCCEEDED`
  payment per booking" is enforced at three layers: the `pay()` idempotency guard, the booking's
  `@Version` optimistic lock, and a partial unique index. The first two return proper responses;
  the index is a last-resort DB guarantee. If it ever fires, a path bypassed both app-layer
  guards — an anomaly worth a `500` + alert, not a routine `409`.
