# ADR 0003: Booking–Ticket Association — FK-based `@OneToMany`

## Status
Accepted — 2026-07-15

## Context
A booking holds one or more tickets. The relationship is **one-to-many**: a
booking has many tickets, and a ticket belongs to **at most one** booking.

[`design.md`](../design.md) §10 originally modelled this as a native Postgres
array column on the booking row: `bookings.ticket_ids BIGINT[]`.
[ADR-0002](0002-database-choice.md) explicitly flagged that this ADR (0003)
would revisit `ticket_ids` in favour of a proper JPA association, noting it
"would only reinforce the relational fit." This ADR records that decision and
the specific *form* of the association.

The read/write paths depend on navigating booking → tickets: `hold()` marks a
booking's tickets `HELD`, `confirm()`/`cancel()` walk them, and the expiry
sweep releases them. Referential integrity matters — the whole reason for a
relational store (ADR-0002) is that "two buyers race for one seat, one must
lose atomically," so a ticket must not silently belong to two bookings.

## Options Considered

### Array column — `bookings.ticket_ids BIGINT[]` (design.md's original)
- **For**: One column, no join, native Postgres array type.
- **Against**: It is not a real association. No foreign-key integrity — the
  array can hold a ticket id that doesn't exist, or the *same* ticket id in two
  bookings, with nothing at the DB layer to stop it. No navigation from a ticket
  back to its booking. Awkward in JPA: needs a custom array type/converter, no
  lazy loading, no cascade, and "which booking holds ticket X?" becomes an
  array-containment scan instead of an indexed FK lookup. It throws away exactly
  the relational guarantees ADR-0002 chose Postgres for.

### Unidirectional `@OneToMany` (join table)
`@OneToMany` on `Booking` with no `mappedBy` → Hibernate creates a
`bookings_tickets` join table.
- **For**: One annotation, no field on `Ticket`.
- **Against**: A join table is the idiomatic shape for *many-to-many*, not
  one-to-many; it doesn't enforce one-booking-per-ticket without an extra unique
  constraint on the ticket column, and it adds a table plus an extra join for no
  benefit. This was effectively the pre-refactor state — and worse, it coexisted
  with an unused `Ticket.booking @ManyToOne`, so the relationship was mapped
  *twice*: the join table (actually populated) plus a dead `tickets.booking_id`
  FK column that no code ever set.

### FK-based bidirectional `@OneToMany(mappedBy) + @ManyToOne` (chosen)
The FK lives on the many side: `Ticket.booking @ManyToOne` owns
`tickets.booking_id`; `Booking.tickets` is `@OneToMany(mappedBy = "booking")`.
- **For**: The idiomatic one-to-many mapping. The FK enforces referential
  integrity and, being single-valued on the ticket, structurally guarantees a
  ticket belongs to at most one booking. Navigable both ways; supports lazy
  loading and the `getTickets()` the sweeps rely on. No join table. Reuses the
  `booking_id` column that already existed (previously dead), turning redundant
  schema into the single source of truth.
- **Against**: The owning side is `Ticket.booking`, so application code must set
  it (`ticket.setBooking(booking)`) — setting only `booking.setTickets(...)`
  (the inverse side) writes nothing. Bidirectional mappings carry the usual
  "keep both sides consistent" burden.

## Decision
Model the association **FK-based**: `Ticket` owns it via
`@ManyToOne` (`tickets.booking_id`), and `Booking.tickets` is
`@OneToMany(mappedBy = "booking")`. `BookingService.hold()` persists the link by
setting the owning side on each ticket and saving the booking before the tickets
(so the FK target exists). The `bookings.ticket_ids` array column from
design.md §10 is superseded.

Deciding factor: this is the natural one-to-many mapping and the only option
that enforces "a ticket belongs to at most one booking" at the database layer —
consistent with ADR-0002's reason for choosing a relational store.

## Consequences
- `tickets.booking_id` is the single source of truth for the association and
  enforces integrity via its FK; there is no join table.
- Application code must set the owning side (`ticket.setBooking(booking)`) and
  order the booking save before the ticket saves. Tests/fixtures that build a
  booking by hand must do the same — setting only the inverse side persists
  nothing under `mappedBy`.
- The `bookings_tickets` join table created by the earlier mapping is now
  unused. `ddl-auto=update` does not drop it, so it lingers empty on existing
  databases (harmless) and is simply never created on fresh ones.
- Dev scripts (`scripts/seed-dev.sql`, `scripts/reset-dev.sql`) release
  `tickets.booking_id` before deleting bookings, per the FK direction.
- A released ticket (`expire()`/`cancel()`) keeps its `booking_id` pointing at
  the now-terminal booking until the next `hold()` overwrites it; nulling it on
  release is an optional future tidy-up.
- design.md §10's `bookings.ticket_ids BIGINT[]` is superseded and should be
  updated to reflect the join-column mapping (doc follow-up).
