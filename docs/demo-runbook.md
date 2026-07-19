# Demo Run Book

A copy-paste walkthrough of the whole system over `curl`: browse → book (open event) →
the waiting room (queued event) → admin teardown. Runs against the **dev** database with
demo-friendly queue rates so the waiting room is actually visible by hand.

Endpoints return JSON; the examples pipe through [`jq`](https://jqlang.github.io/jq/) for
readability and to extract ids. `jq` is optional — drop the `| jq ...` and read by eye if you
don't have it (`brew install jq`).

---

## 0. Prerequisites

- **Postgres** running, with the dev database created:
  ```bash
  createdb ticketmaster_dev
  ```
- **Redis** running on `localhost:6379`.
- **Java 21** on the path (the app is Spring Boot 4 / Java 21).

Optional but recommended for a clean run — clear any stale queue state in Redis:
```bash
redis-cli flushdb
```

---

## 1. Start the app on the dev profile

The dev profile points at `ticketmaster_dev` and sets `queue.admit-rate=2`,
`queue.admit-interval-ms=5000` — small numbers so you can watch the escape hatch, the backlog,
and the drain in real time. Boot it once so Hibernate (`ddl-auto=update`) creates the tables:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Leave it running in its own terminal. Everything below runs in a second terminal.

---

## 2. Seed the demo data

One venue (20 seats), two events (one queue-gated, one open), two users, one ticket per
seat/event. Everything is tagged `[dev-seed]` so it can be cleanly removed later.

```bash
psql -d ticketmaster_dev -f scripts/seed-dev.sql
```

The final `SELECT` prints the ids you need:

```
 queued_event_id | open_event_id | alice_id | bob_id | tickets_created
-----------------+---------------+----------+--------+-----------------
               1 |             2 |        1 |      2 |              40
```

## 3. Capture those ids as shell variables

Substitute the numbers the seed printed:

```bash
QUEUED=1        # queued_event_id  (requires_queue = true)
OPEN=2          # open_event_id    (requires_queue = false)
ALICE=1         # alice_id
BOB=2           # bob_id
BASE=localhost:8080
```

---

## 4. Browse / discover (read side)

```bash
# GET /events is paginated: the response is { "content": [ ...events... ], "page": {...} }.
# Pipe `.content` to see just the matches; add ?page/size/sort to control paging (below).

# whole catalog (first page)
curl -s $BASE/events | jq '.content'

# filter by status
curl -s "$BASE/events?status=ON_SALE" | jq '.content'

# filter by name (case-insensitive substring)
curl -s "$BASE/events?name=jazz" | jq '.content'

# filter by city (matched via the venue join, case-insensitive)
curl -s "$BASE/events?city=san%20francisco" | jq '.content'

# filter by performer (case-insensitive substring)
curl -s "$BASE/events?performer=phoebe" | jq '.content'   # -> the queued Fever Dream Tour

# filter by start-time range (ISO-8601). seed events start ~30 days out, so a today..+60d
# window includes them, while an upper bound of "now" excludes them (they're in the future):
FROM=$(date -u +%Y-%m-%dT00:00:00Z)
TO=$(date -u -v+60d +%Y-%m-%dT00:00:00Z)                  # BSD/macOS date syntax
curl -s "$BASE/events?from=$FROM&to=$TO" | jq '.content'  # -> both seed events
curl -s "$BASE/events?to=$FROM" | jq '.content'           # -> []  (nothing starts before today)

# filters are ANDed together: on-sale "jazz" events in SF
curl -s "$BASE/events?status=ON_SALE&city=san%20francisco&name=jazz" | jq '.content'

# pagination + sort: one event per page, newest first — shows the page metadata block
curl -s "$BASE/events?size=1&page=0&sort=startsAt,desc" | jq '{names: [.content[].name], page}'

# a single event, and the venue list
curl -s $BASE/events/$OPEN | jq
curl -s $BASE/venues | jq

# tickets for an event (note the AVAILABLE status)
curl -s $BASE/events/$OPEN/tickets | jq
```

---

## 5. Open event — book straight through (no queue)

`Tuesday Night Jazz` has `requires_queue = false`, so booking needs no access token.

```bash
# grab the first available ticket id for the open event
OPEN_TICKET=$(curl -s $BASE/events/$OPEN/tickets | jq '[.[] | select(.status=="AVAILABLE")][0].id')
echo "booking ticket $OPEN_TICKET"

# HOLD -> creates a PENDING booking with an expiry. Capture the response so we can
# both show it and pull its id.
HOLD=$(curl -s -XPOST $BASE/bookings/hold -H 'Content-Type: application/json' \
  -d "{\"userId\":$ALICE,\"eventId\":$OPEN,\"ticketIds\":[$OPEN_TICKET],\"idempotencyKey\":\"open-1\"}")
echo "$HOLD" | jq                          # note status: "PENDING" and the expiry
OPEN_BOOKING=$(echo "$HOLD" | jq -r '.id')
echo "OPEN_BOOKING=$OPEN_BOOKING"

# PAY -> flips PENDING to CONFIRMED
curl -s -XPOST $BASE/bookings/$OPEN_BOOKING/pay | jq

# verify
curl -s $BASE/bookings/$OPEN_BOOKING | jq '{id, status}'
curl -s $BASE/bookings/$OPEN_BOOKING/payments | jq
```

**Idempotency:** re-run the exact same HOLD command (same `idempotencyKey`) — you get the *same*
booking back, not a second one.

---

## 6. Queued event — the waiting room (the centerpiece)

`Fever Dream Tour` has `requires_queue = true`. This is where the queue, escape hatch, backlog
drain, and rate limiter all show up.

### 6a. Booking is blocked without an access token → 403

```bash
Q_TICKET=$(curl -s $BASE/events/$QUEUED/tickets | jq '[.[] | select(.status=="AVAILABLE")][0].id')

curl -s -o /dev/null -w "HTTP %{http_code}\n" -XPOST $BASE/bookings/hold \
  -H 'Content-Type: application/json' \
  -d "{\"userId\":$ALICE,\"eventId\":$QUEUED,\"ticketIds\":[$Q_TICKET],\"idempotencyKey\":\"q-blocked\"}"
# -> HTTP 403  (QueueAccessRequiredException)
```

### 6b. Join the queue — the escape hatch admits the first 2

With `admit-rate=2`, the first two joiners on an empty backlog are fast-tracked straight to
`ADMITTED`; everyone after lands in the backlog as `WAITING`.

```bash
T1=$(curl -s -XPOST $BASE/events/$QUEUED/queue); echo "T1=$T1"
curl -s $BASE/events/$QUEUED/queue/$T1 | jq        # ADMITTED

T2=$(curl -s -XPOST $BASE/events/$QUEUED/queue); echo "T2=$T2"
curl -s $BASE/events/$QUEUED/queue/$T2 | jq        # ADMITTED

T3=$(curl -s -XPOST $BASE/events/$QUEUED/queue); echo "T3=$T3"
curl -s $BASE/events/$QUEUED/queue/$T3 | jq        # WAITING, position 0

T4=$(curl -s -XPOST $BASE/events/$QUEUED/queue); echo "T4=$T4"
curl -s $BASE/events/$QUEUED/queue/$T4 | jq        # WAITING, position 1

T5=$(curl -s -XPOST $BASE/events/$QUEUED/queue); echo "T5=$T5"
curl -s $BASE/events/$QUEUED/queue/$T5 | jq        # WAITING, position 2
```

### 6c. Rate limiter kicks in → 429

That was 5 joins from one IP. The default limit is 5 per 10s per `(event, IP)`, so a 6th join
right now is rejected:

```bash
curl -s -o /dev/null -w "HTTP %{http_code}\n" -XPOST $BASE/events/$QUEUED/queue
# -> HTTP 429  (rate limit exceeded)
```

To see the whole `200`-then-`429` boundary on a fresh window, wait ~10s for it to reset, then
fire a burst of joiners and watch the 6th onward flip to `429`:

```bash
sleep 10                                   # let the 10s rate-limit window reset
for i in $(seq 1 8); do
  printf "join %d -> " "$i"
  curl -s -o /dev/null -w "HTTP %{http_code}\n" -XPOST $BASE/events/$QUEUED/queue
done
# join 1 -> HTTP 200
# join 2 -> HTTP 200
# join 3 -> HTTP 200
# join 4 -> HTTP 200
# join 5 -> HTTP 200
# join 6 -> HTTP 429
# join 7 -> HTTP 429
# join 8 -> HTTP 429
```

Two things to note: the `sleep 10` matters — without it you're still inside 6b's window, so
*all* eight joins return `429` (which also proves the limiter, just less cleanly). And the five
successful joins here add to the backlog, so the drain in 6d will take a couple extra `admit()`
ticks to clear.

### 6d. Watch the backlog drain

The `admit()` job runs every 5s and admits `admit-rate` (2) tokens per tick, oldest first.

```bash
sleep 6
curl -s $BASE/events/$QUEUED/queue/$T3 | jq        # now ADMITTED
curl -s $BASE/events/$QUEUED/queue/$T5 | jq        # WAITING, position shifted down to 0

sleep 6
curl -s $BASE/events/$QUEUED/queue/$T5 | jq        # now ADMITTED
```

### 6e. Book with an admitted token

```bash
# capture the hold response so we can both show it and pull its id
HOLD=$(curl -s -XPOST $BASE/bookings/hold -H 'Content-Type: application/json' \
  -d "{\"userId\":$ALICE,\"eventId\":$QUEUED,\"ticketIds\":[$Q_TICKET],\"idempotencyKey\":\"q-1\",\"accessToken\":\"$T1\"}")
echo "$HOLD" | jq
Q_BOOKING=$(echo "$HOLD" | jq -r '.id')
echo "Q_BOOKING=$Q_BOOKING"

curl -s -XPOST $BASE/bookings/$Q_BOOKING/pay | jq  # CONFIRMED
```

---

## 7. Extras (optional)

```bash
# everything a user has booked
curl -s $BASE/users/$ALICE/bookings | jq

# refund a confirmed booking (payment -> REFUNDED)
curl -s -XPOST $BASE/bookings/$OPEN_BOOKING/refund | jq

# cancel a PENDING booking: make a fresh hold on the open event, then cancel it
FREE_TICKET=$(curl -s $BASE/events/$OPEN/tickets | jq '[.[] | select(.status=="AVAILABLE")][0].id')
CANCEL_ME=$(curl -s -XPOST $BASE/bookings/hold -H 'Content-Type: application/json' \
  -d "{\"userId\":$BOB,\"eventId\":$OPEN,\"ticketIds\":[$FREE_TICKET],\"idempotencyKey\":\"bob-1\"}" | jq .id)
curl -s -XPOST $BASE/bookings/$CANCEL_ME/cancel | jq '{id, status}'   # CANCELLED

# cancel the whole event: refunds/cancels its bookings, cancels its tickets,
# AND purges all of its Redis queue state in one shot
curl -s -XPOST $BASE/events/$QUEUED/cancel | jq
curl -s $BASE/events/$QUEUED | jq '{id, status}'   # CANCELLED
```

To confirm the queue purge after cancelling, the backlog key is gone:
```bash
redis-cli zcard "queue:$QUEUED"          # (integer) 0
redis-cli sismember queue:active-events $QUEUED   # (integer) 0
```

---

## 8. Reset between runs

**Targeted reset** — remove only the seeded rows, leaving anything else in the dev DB untouched
(handles FK order, releasing each ticket's `booking_id` before deleting its booking):

```bash
psql -d ticketmaster_dev -f scripts/reset-dev.sql
redis-cli flushdb        # clear queue/access/rate-limit keys
```

**Full wipe** — blow away *everything* in the dev DB and reset the id sequences (so the next seed
starts at venue 1, event 1, …). `CASCADE` handles FK order; `RESTART IDENTITY` restarts the
sequences:

```bash
psql -d ticketmaster_dev -c "TRUNCATE TABLE payments, bookings, tickets, seats, events, venues, users RESTART IDENTITY CASCADE;"
redis-cli flushdb
```

Then re-run from step 2.

---

## Endpoint cheat sheet

| Method & path | Purpose |
|---|---|
| `GET /events?name=&status=&city=&performer=&from=&to=&page=&size=&sort=` | Browse / filter events (filters optional + ANDed); paginated response `{content, page}` |
| `GET /events/{id}` | One event |
| `GET /events/{id}/tickets` | Tickets for an event |
| `POST /events` | Create event (needs a venue with seats) |
| `POST /events/{id}/cancel` | Cancel event + refund + purge queue |
| `GET /venues` · `POST /venues` | List / create venues |
| `POST /events/{id}/queue` | Join the waiting room → returns a token |
| `GET /events/{id}/queue/{token}` | Queue status: WAITING / ADMITTED / INVALID |
| `POST /bookings/hold` | Reserve tickets (token required if event is queue-gated) |
| `POST /bookings/{id}/pay` | Pay → CONFIRMED |
| `POST /bookings/{id}/cancel` | Cancel a booking |
| `POST /bookings/{id}/refund` | Refund a paid booking |
| `GET /bookings/{id}` · `GET /users/{id}/bookings` | Look up bookings |

## Response codes worth pointing out

| Code | When |
|---|---|
| `403` | Booking a queue-gated event without a valid access token |
| `429` | More than `queue.enqueue-limit` joins per window from one IP |
| `409` | State-machine violations (e.g. paying a cancelled booking), or a concurrent-modification conflict (optimistic/row-lock loser) |
| `400` | Bad request body, or creating an event against a seatless venue |
