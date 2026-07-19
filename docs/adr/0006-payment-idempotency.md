# ADR 0006: Payment Idempotency — Booking-Scoped Guard + Partial Unique Index

## Status
Accepted — 2026-07-19

## Context
`POST /bookings/{id}/pay` charges and confirms a held booking. Client retries (a network timeout
where the request actually succeeded) and payment webhooks can fire the *same* pay twice, so the
step must be idempotent and must never double-charge.

The starting implementation created a `SUCCEEDED` payment row, then called `confirm()`:

- A retry created a *second* payment row, then `confirm()` rejected the already-`CONFIRMED`
  booking, and the surrounding `@Transactional` rolled the second payment back.
- So no duplicate row persisted — but the retry got a **409** instead of the original success,
  and correctness leaned on a rollback *side-effect*, not on an explicit invariant.

Two things shape the decision:

- The invariant we want is **"a booking is paid at most once"**, and a retry should be
  **idempotent** (return the original result, not an error).
- `pay()` conceptually charges an external provider (Stripe); you must **validate before charging**,
  never charge-then-roll-back — a real charge isn't undone by a DB rollback.

Booking *hold* already has its own idempotency key ([design.md](../design.md) §9.5); this ADR is
specifically about the **payment** step. The concurrent case leans on the `Booking` `@Version`
optimistic lock from [ADR-0005](0005-response-dtos-osiv.md) / §9.6.

## Options Considered

### Rely on the existing `confirm()` guard + transaction rollback (status quo)
- **For**:
  - No new code.
  - No duplicate payment row persists (the transaction rolls back).
- **Against**:
  - A legitimate retry gets a **409**, not the original success — not idempotent semantics.
  - Correctness depends on a rollback side-effect rather than a stated invariant.
  - Creates (then discards) a payment *before* validating the booking — against a real provider
    that's a charge you'd have to refund.

### Client-supplied idempotency key on the payment (Stripe-style)
- **For**:
  - The general, industry-standard approach: client sends an `Idempotency-Key`, stored uniquely
    on the payment; a duplicate returns the original.
  - Covers retries even *before* confirm, and across any number of attempts.
- **Against**:
  - Adds a required header/param to the pay endpoint — more API surface and client burden for a
    demo.
  - Redundant here: a booking is paid exactly once, so the booking id is already a natural
    idempotency scope.

### Booking-scoped guard + partial unique index (chosen)
The booking is the idempotency scope: `pay()` returns the booking unchanged if it's already
`CONFIRMED`, rejects non-payable states up front, and a partial unique index enforces "one
`SUCCEEDED` payment per booking" at the DB.
- **For**:
  - Needs **no new request param** — the endpoint stays `POST /bookings/{id}/pay` with no body.
  - Fits the domain exactly: a booking is paid once, so `CONFIRMED ⟺ paid`.
  - **Validates payability before charging** (reject `EXPIRED`/`CANCELLED` up front) — no
    charge-then-rollback.
  - Layered: the app-level guard returns idempotent success; the `@Version` lock (§9.6) rejects a
    concurrent double-pay; the partial index is a last-resort DB backstop.
- **Against**:
  - Narrower than a client key — it's idempotent at the granularity of "a paid booking," not
    arbitrary pre-confirm retry semantics.
  - The partial index can't be expressed in JPA (`@Index` has no `WHERE` predicate), so it lives
    in `schema.sql` / a Flyway migration, outside the entity mapping.
  - If the index ever *fires* (a path bypassing both the guard and `@Version`), it surfaces as an
    unmapped `DataIntegrityViolationException` → **500**. Intentional — reaching it is anomalous
    (see [README](../../README.md) known-limitations).

## Decision
Use the **booking-scoped guard plus the partial unique index**. In `pay()`:

- if the booking is already `CONFIRMED`, return it (idempotent — no second charge);
- if it is not `PENDING`, throw `InvalidBookingStateException` (reject `EXPIRED`/`CANCELLED`
  before charging);
- otherwise create the payment and `confirm()`.

The DB enforces the invariant with `CREATE UNIQUE INDEX ... ON payments (booking_id) WHERE status
= 'SUCCEEDED'`. The concurrent double-pay is caught by the `Booking` `@Version` optimistic lock
(ADR-0005 / §9.6).

Deciding factors: it matches the domain (a booking is paid once), needs no new API surface,
validates before charging, and layers an app-level guarantee with a DB-level one.

## Consequences
- A retried `pay` on a confirmed booking returns **200 with the booking**, not a 409.
- The partial index lives in `src/main/resources/schema.sql`, applied at startup via
  `spring.sql.init.mode=always` + `spring.jpa.defer-datasource-initialization=true` (so Hibernate
  creates the table first). `ddl-auto`/`@Index` can't express a partial index — a concrete case
  for Flyway/Liquibase in production.
- If the index ever fires, it's an unmapped `500` (anomaly-worthy), recorded in the README
  known-limitations rather than mapped to a routine `409`.
- A client-supplied idempotency key stays the path if payment ever needs to be idempotent
  *before* confirm (e.g. deduping an external webhook independent of booking state).
- Builds on [ADR-0005](0005-response-dtos-osiv.md): the `@Version` optimistic lock it introduced
  is what makes the *concurrent* double-pay safe here.
