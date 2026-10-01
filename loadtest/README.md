# Load test

A standalone Python script, `loadtest.py`, that drives the running app over HTTP. It covers three
scenarios, each checked against PostgreSQL afterwards:

| Phase       | What it does                                                                                              | Pass criteria                                                               |
|-------------|-----------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| `spike`     | N buyers arrive at once: join queue → poll until `ADMITTED` → hold 1 seat → pay. Limited inventory (`--seats`). | 0 seats sold twice; confirmed bookings = BOOKED tickets = SUCCEEDED payments ≤ inventory |
| `ratelimit` | One IP bursts `--abuser-requests` joins while `--normal-users` distinct IPs join once each.                  | abuser gets `queue.enqueue-limit` 200s and the rest `429`; every normal user gets 200 |
| `hotseat`   | `--contenders` buyers `POST /bookings/hold` the **same** ticket concurrently, for each of `--rounds` tickets. | exactly 1 winner per round; losers get `409`                                 |

It needs no project dependencies. `uv` fetches `httpx` into a throwaway environment (PEP 723 header),
and seats and users, which have no write API, are inserted with `psql`.

## Run

```bash
# 1. isolated database + Redis DB, so the test never touches ticketmaster / _dev / _test
createdb ticketmaster_loadtest

# 2. build and start the app under the prod profile (honors X-Forwarded-For, see "Client IPs" below)
./mvnw -q -DskipTests package
java -jar target/ticketmaster-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=prod \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/ticketmaster_loadtest \
  --spring.data.redis.database=15

# 3. run phases (from the repo root). Results print as a table and are saved to loadtest/results/*.json
uv run loadtest/loadtest.py spike --buyers 2000 --seats 200
uv run loadtest/loadtest.py spike --buyers 2000 --seats 200 --queue-only   # waiting room only, no booking
uv run loadtest/loadtest.py spike --buyers 2000 --seats 200 --cold         # include TCP connects in the spike
uv run loadtest/loadtest.py ratelimit --abuser-requests 50 --normal-users 50
uv run loadtest/loadtest.py hotseat --rounds 50 --contenders 100

# 4. clean up
dropdb ticketmaster_loadtest
redis-cli -n 15 flushdb
```

`--db` (default `ticketmaster_loadtest`) must name the database the app is pointed at. `--base`
defaults to `http://localhost:8080`. Pass `--offline` to `uv run` if PyPI isn't reachable and
`httpx` is already in the uv cache.

## How it works

- **Client IPs.** `QueueController.enqueue` rate-limits on `request.getRemoteAddr()`. Locally,
  every request comes from `127.0.0.1`, so all simulated users would share one bucket of 5 joins
  per 10s. The `prod` profile sets `server.forward-headers-strategy=FRAMEWORK`, which makes
  `getRemoteAddr()` return the `X-Forwarded-For` client IP, so each buyer sends a distinct
  `X-Forwarded-For: 10.x.y.z`. The `ratelimit` phase also sends 8 requests without the header to
  show the shared-`127.0.0.1` behavior.
- **Warm connections (default).** Each buyer has its own keep-alive connection, opened before
  the spike, like a fan sitting on the on-sale page. Without this, thousands of simultaneous
  `connect()`s overflow macOS's accept backlog (`kern.ipc.somaxconn=128`, Tomcat `accept-count`
  100), and the results measure SYN retransmits rather than the app. Use `--cold` to include
  the connects.
- **Seat choice.** Each buyer picks a random seat from the page-load seat map. On a `409`, it
  re-fetches `GET /events/{id}/tickets?status=AVAILABLE` and picks again, up to `--max-attempts`.
  It gives up when that list is empty or the event is no longer on sale.
- **409 breakdown.** Hold `409`s are split by response body:
  - `lock_conflict`: `SELECT … NOWAIT` lost the row lock (`ConcurrencyFailureException`).
  - `ticket_unavailable`: the row was already `HELD` or `BOOKED`.
  - `event_not_on_sale`: the event is sold out.
- **Oversell checks.** These run in `verify_spike`, and the queries are saved in the JSON under
  `db_queries`:
  - `seats_with_gt1_confirmed_booking`: count of seats with more than one `CONFIRMED` booking.
  - `confirmed_bookings_without_ticket`: `tickets.booking_id` is a single FK, so a double sale
    would leave a paid booking whose ticket was re-pointed elsewhere.
  - `confirmed_bookings`, `booked_tickets`, `succeeded_payments` and `inventory`: all must match
    (≤ inventory).
  - Client side: ticket ids that appear in more than one successful hold or pay response.

## Caveats

- Everything (app, Postgres, Redis, client) runs on one laptop over localhost, so there is no
  network latency and all components compete for the same CPUs.
- The client is a single Python/asyncio process. Throughput plateaus at ~2.6–3.0k req/s whether
  N is 1,000, 2,000 or 5,000. Three client processes run in parallel each still reached
  ~2.3k req/s, so the plateau is the client, not the server. Treat single-client req/s as a
  lower bound.
- The app runs with its defaults: Hikari pool 10, Tomcat 200 threads, `queue.admit-rate=500`
  per 1s.

## Findings from running it

The first run used unchanged production code. Fixes made since are marked **Fixed**; a second run
after them confirmed what changed.

- **Transient `INVALID` from the queue-status poll. Fixed.** `QueueService.admit()` popped tokens
  atomically in Lua (`admitCleanup.lua`), then set each token's access key in a separate Java
  loop. A poll that landed between the two found the token in neither place and got `INVALID`
  from `checkStatus()`. In most runs, some buyers went `INVALID` → `ADMITTED`, up to 738 of 2,000;
  a client that treats `INVALID` as terminal would drop them. The script now sets the access keys
  in the same atomic step as the pop (pinned by
  `QueueServiceTest.admitScriptGrantsAccessInTheSameStepAsThePop`). Second run: 0 of 80,493
  status polls returned `INVALID`, against 2,239 in the first.
- **Event can stay `ON_SALE` after it sells out. Fixed.** `BookingService.confirm()` calls
  `EventService.markSoldOutIfLastTicketBooked`. Two concurrent confirms could each still see the
  other's ticket as `HELD` (write skew under READ COMMITTED), so neither flipped the event. Seen on
  3 of 13 sold-out events in the first run and 3 of 12 in the second. No seat was oversold, but
  late buyers got `409 ticket unavailable` instead of `409 not on sale`, and the waiting room kept
  admitting fans to an event with no inventory. The check now locks and re-reads the event row
  before counting (pinned by `EventSoldOutRaceTest`); not yet re-measured under load.
- **Hibernate emits `FOR NO KEY UPDATE … NOWAIT`, not `FOR UPDATE NOWAIT`.** Confirmed with
  `--logging.level.org.hibernate.SQL=DEBUG`. It's still an exclusive lock between concurrent
  holds, which is all the double-booking guard needs. Fixed since: `docs/design.md` and the
  `TicketRepository` comment now say `FOR NO KEY UPDATE NOWAIT`.
- **`GET /events/{id}/tickets` is N+1.** The eager `@ManyToOne seat` on `Ticket` is loaded
  with one `SELECT` per ticket, so 1 + N queries per call. On a 200-ticket event it served
  ~643 req/s, against ~27k req/s for `GET /events/{id}`.
- **Every queue join hits Postgres.** `enqueue()` calls `getEventIfOnSale` before touching
  Redis (`QueueService.java:53`). Only the rate-limit rejection (`:47-50`) is Redis-only.
