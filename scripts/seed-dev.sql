-- Seeds enough data to walk the full booking flow by hand.
--
--   psql -d ticketmaster -f scripts/seed-dev.sql
--
-- The app has no write API, so without this there is no way to create an event with
-- requires_queue = true, and the queue package is unreachable at runtime.
--
-- Run the app at least once first so Hibernate (ddl-auto=update) has created the tables.
--
-- Everything here is tagged 'dev-seed' in users.name and events.name so scripts/reset-dev.sql
-- can find and remove exactly these rows. Emails are deliberately @dev.seed rather than
-- @example.com: the test suite commits against this same database and hardcodes
-- alice@example.com / bob@example.com, and users.email is unique.

BEGIN;

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
