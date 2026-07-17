-- Removes everything scripts/seed-dev.sql created, plus any bookings/payments made against it.
--
--   psql -d ticketmaster_dev -f scripts/reset-dev.sql
--
-- Deletes ONLY seed-tagged rows and their descendants. Never truncates, so any data you
-- created yourself (and anything the test suite committed) is left alone.
--
-- It also cleans up the rows left behind by the earlier in-app CommandLineRunner seeder,
-- which used @example.com emails and so collided with the hardcoded users in the test suite.

BEGIN;

CREATE TEMP TABLE seed_events ON COMMIT DROP AS
SELECT id FROM events
WHERE name LIKE '%[dev-seed]%'
   OR name IN ('Fever Dream Tour', 'Tuesday Night Jazz');   -- legacy CommandLineRunner rows

CREATE TEMP TABLE seed_venues ON COMMIT DROP AS
SELECT id FROM venues
WHERE name LIKE '%[dev-seed]%'
   OR name = 'The Fillmore';                                -- legacy CommandLineRunner row

CREATE TEMP TABLE seed_bookings ON COMMIT DROP AS
SELECT id FROM bookings WHERE event_id IN (SELECT id FROM seed_events);

-- The Booking->Ticket link is the tickets.booking_id FK (Ticket owns it); release any ticket
-- pointing at a booking we're about to delete before deleting the bookings. (These tickets all
-- belong to seed events and are deleted just below.)
UPDATE tickets SET booking_id = NULL, status = 'AVAILABLE'
 WHERE booking_id IN (SELECT id FROM seed_bookings);
DELETE FROM payments WHERE booking_id IN (SELECT id FROM seed_bookings);
DELETE FROM bookings WHERE id         IN (SELECT id FROM seed_bookings);
DELETE FROM tickets  WHERE event_id   IN (SELECT id FROM seed_events);
DELETE FROM events   WHERE id         IN (SELECT id FROM seed_events);
DELETE FROM seats    WHERE venue_id   IN (SELECT id FROM seed_venues);
DELETE FROM venues   WHERE id         IN (SELECT id FROM seed_venues);

DELETE FROM users
WHERE email IN ('alice@dev.seed', 'bob@dev.seed')
   OR (name IN ('Alice', 'Bob') AND email IN ('alice@example.com', 'bob@example.com'));

COMMIT;

-- Redis holds the queue's state, and it is not in Postgres. To clear it too:
--
--   redis-cli --scan --pattern 'queue:*'  | xargs redis-cli del
--   redis-cli --scan --pattern 'access:*' | xargs redis-cli del
