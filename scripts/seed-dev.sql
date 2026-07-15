-- Seeds enough data to walk the full booking flow by hand, against the dev database.
--
--   psql -d ticketmaster_dev -f scripts/seed-dev.sql
--
-- A shortcut past the write API (POST /venues, POST /events): one command gives you a
-- venue with seats plus a queue-gated and an open event, rather than several curl calls.
--
-- Boot the dev profile at least once first so Hibernate (ddl-auto=update) has created the
-- tables:  ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
--
-- Idempotent: it clears its own previously-seeded rows before inserting, so you can re-run
-- it directly (no separate reset needed) without colliding on the users.email constraint.
--
-- Everything here is tagged 'dev-seed' in users.name and events.name so scripts/reset-dev.sql
-- can find and remove exactly these rows.

BEGIN;

-- Clear any rows a previous run of THIS script created, plus bookings/payments made against
-- them, so the inserts below never collide. Scoped strictly to '[dev-seed]'-tagged rows (and
-- the two seed emails) and their descendants; nothing else is touched. For a deeper clean that
-- also removes legacy CommandLineRunner rows, use scripts/reset-dev.sql.
CREATE TEMP TABLE _seed_events ON COMMIT DROP AS
SELECT id FROM events WHERE name LIKE '%[dev-seed]%';

CREATE TEMP TABLE _seed_venues ON COMMIT DROP AS
SELECT id FROM venues WHERE name LIKE '%[dev-seed]%';

-- Bookings to clear = anything on a seed event OR made by a seed user. The user clause matters
-- because a seed user may have booked a non-seed event (e.g. a legacy CommandLineRunner event);
-- those bookings still reference the user we're about to delete, so they must go first.
CREATE TEMP TABLE _seed_bookings ON COMMIT DROP AS
SELECT id FROM bookings
WHERE event_id IN (SELECT id FROM _seed_events)
   OR user_id  IN (SELECT id FROM users WHERE email IN ('alice@dev.seed', 'bob@dev.seed'));

-- The Booking->Ticket link is the tickets.booking_id FK (Ticket owns it). Release any ticket
-- that points at a booking we're about to delete -- including legacy-event tickets a seed user
-- booked, which we keep -- so the FK no longer blocks the booking delete. (seed-event tickets are
-- deleted outright below anyway.)
UPDATE tickets SET booking_id = NULL, status = 'AVAILABLE', hold_expires_at = NULL
 WHERE booking_id IN (SELECT id FROM _seed_bookings);
DELETE FROM payments WHERE booking_id IN (SELECT id FROM _seed_bookings);
DELETE FROM bookings WHERE id         IN (SELECT id FROM _seed_bookings);
DELETE FROM tickets  WHERE event_id   IN (SELECT id FROM _seed_events);
DELETE FROM events   WHERE id         IN (SELECT id FROM _seed_events);
DELETE FROM seats    WHERE venue_id   IN (SELECT id FROM _seed_venues);
DELETE FROM venues   WHERE id         IN (SELECT id FROM _seed_venues);
DELETE FROM users    WHERE email IN ('alice@dev.seed', 'bob@dev.seed');

WITH venue AS (
    INSERT INTO venues (name, address, city)
    VALUES ('The Fillmore [dev-seed]', '1805 Geary Blvd', 'San Francisco')
    RETURNING id
),
seats AS (
    INSERT INTO seats (venue_id, section, row_label, seat_number)
    SELECT venue.id, 'GA', 'A', n::text
    FROM venue, generate_series(1, 20) AS n
    RETURNING id
),
queued_event AS (
    -- exercises the waiting room
    INSERT INTO events (name, performer, venue_id, starts_at, on_sale_at, status, requires_queue)
    SELECT 'Fever Dream Tour [dev-seed]', 'Phoebe Bridgers', venue.id,
           now() + interval '30 days', now() - interval '1 day', 'ON_SALE', true
    FROM venue
    RETURNING id
),
open_event AS (
    -- books straight through, no token needed
    INSERT INTO events (name, performer, venue_id, starts_at, on_sale_at, status, requires_queue)
    SELECT 'Tuesday Night Jazz [dev-seed]', 'The Bad Plus', venue.id,
           now() + interval '30 days', now() - interval '1 day', 'ON_SALE', false
    FROM venue
    RETURNING id
),
tickets AS (
    -- one ticket per seat, per event
    INSERT INTO tickets (event_id, seat_id, price_cents, status)
    SELECT e.id, seats.id, 7500, 'AVAILABLE'
    FROM seats,
         (SELECT id FROM queued_event UNION ALL SELECT id FROM open_event) AS e
    RETURNING id
),
seed_users AS (
    INSERT INTO users (email, name)
    VALUES ('alice@dev.seed', 'Alice [dev-seed]'),
           ('bob@dev.seed', 'Bob [dev-seed]')
    RETURNING id
)
SELECT (SELECT id FROM queued_event)              AS queued_event_id,
       (SELECT id FROM open_event)                AS open_event_id,
       (SELECT min(id) FROM seed_users)           AS alice_id,
       (SELECT max(id) FROM seed_users)           AS bob_id,
       (SELECT count(*) FROM tickets)             AS tickets_created;

COMMIT;

-- Walk the queued event (substitute the ids printed above):
--
--   TOKEN=$(curl -s -XPOST localhost:8080/events/<queued_event_id>/queue)
--   curl localhost:8080/events/<queued_event_id>/queue/$TOKEN
--   curl localhost:8080/events/<queued_event_id>/tickets
--   curl -XPOST localhost:8080/bookings/hold -H 'Content-Type: application/json' \
--     -d '{"userId":<alice_id>,"eventId":<queued_event_id>,"ticketIds":[<id>],
--          "idempotencyKey":"k1","accessToken":"'"$TOKEN"'"}'
--
-- With the default admit-rate=500 the waiting room is invisible by hand. To see the
-- escape hatch, the backlog and the drain, boot with the dev profile:
--
--   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   (admit-rate=2, tick=5s)
--
-- Full step-by-step walkthrough: docs/demo-runbook.md
