# ADR 0002: Primary Database — PostgreSQL

## Status
Accepted — 2026-07-12

## Context
[`design.md`](../design.md) §7 calls for the transactional core (bookings,
tickets, payments) to live in a relational DB, chosen specifically for ACID
guarantees: §5/§9 depend on wrapping "check seat availability + mark it held"
in a single atomic transaction (`SELECT ... FOR UPDATE`) so two concurrent
buyers can never win the same seat. That requirement — real row-level locking
inside a transaction, with the DB itself as the source of truth — is the main
axis for this decision, not raw throughput or schema flexibility.

Secondary considerations:
- The schema in design.md (§2, §10) is naturally relational: `events`,
  `venues`, `seats`, `tickets`, `bookings` with foreign keys between them,
  and a `bookings.ticket_ids BIGINT[]`-style column plus a
  `UNIQUE(idempotency_key)` constraint — i.e. the design already leans on
  relational features (FKs, uniqueness constraints, array columns).
  Note: ADR-0003 (Booking entity) may revisit `ticket_ids` in favor of a
  proper `@OneToMany`, which would only reinforce the relational fit.
- §7 also mentions **sharding the transactional DB by `event_id`** later,
  since booking contention is naturally partitioned per event.
- This is a learning project built with Java/Spring Boot ([ADR-0001](0001-language-choice.md)),
  where **Spring Data JPA + Hibernate** is the primary data-access layer —
  the DB choice should be one with first-class, mainstream JPA support.

## Options Considered

### PostgreSQL
- **For**: True `SELECT ... FOR UPDATE` / `FOR UPDATE NOWAIT` row locking
  with strict MVCC semantics — maps directly onto design.md §9.2. Native
  array column type (`BIGINT[]`), which the `bookings.ticket_ids` column in
  §10 uses directly, plus rich constraint support (`UNIQUE`, partial/
  expression indexes) for enforcing invariants like the idempotency key at
  the DB layer, not just in application code. Mature Hibernate dialect
  support. Free, open-source, and the de facto default for new Java/Spring
  projects — most Spring Boot documentation and tutorials assume Postgres,
  which matters for this project's secondary learning goal. Good story for
  the sharding-by-`event_id` extension mentioned in design.md §7 (via
  extensions like Citus, or manual sharding).
- **Against**: Vertical scaling has practical ceilings; horizontal writes
  need extra tooling (Citus, manual sharding) that isn't needed at this
  project's current scale.

### MySQL
- **For**: Also supports `SELECT ... FOR UPDATE`, ACID transactions (InnoDB),
  huge ecosystem, explicitly named as an alternative in design.md §7.
  Extremely common in industry, well-documented Hibernate dialect.
- **Against**: No native array column type — `bookings.ticket_ids` would
  need a join table or a JSON column instead of a first-class array,
  meaning JPA mapping is either a fake-out via JSON or a proper
  `@OneToMany`. Historically weaker standards compliance and locking
  semantics than Postgres (e.g. gap-locking behavior under `REPEATABLE READ`
  is easier to get subtly wrong than Postgres's MVCC model). No strong
  advantage over Postgres for this project's needs.

### MongoDB
- **For**: Flexible schema, easy to get started, good fit if the seat map /
  event data were highly variable in shape.
- **Against**: No multi-document ACID transactions with the maturity Postgres
  has (multi-document transactions exist but are heavier-weight and less
  battle-tested for this exact use case). The core requirement here —
  "two buyers race for one seat, one must lose atomically" — is precisely
  the kind of strongly-consistent, relational, constraint-driven problem
  document stores are a worse fit for. The schema in design.md is already
  relational (FKs between events/venues/seats/tickets/bookings), so a
  document model would fight the domain rather than fit it.

### DynamoDB (or similar managed NoSQL)
- **For**: Scales horizontally with near-zero ops burden, conditional writes
  (`ConditionExpression`) can implement optimistic locking for holds.
  Attractive for the Redis-adjacent hold layer, less so as the primary store.
- **Against**: No relational joins or foreign keys — the events/venues/
  seats/tickets/bookings graph in design.md §2 would need denormalization
  or multiple round-trips. Weaker fit for ad-hoc queries during development/
  learning. Overkill/mismatch for a single-instance learning project with no
  current multi-region requirement.

## Decision
Use **PostgreSQL** as the primary transactional datastore for events,
venues, seats, tickets, and bookings, accessed via Spring Data JPA/
Hibernate, per design.md §7. Redis (per design.md §5–§6, §9.3) remains the
planned option for the hot-path seat-hold layer in front of Postgres, not a
replacement for it; Elasticsearch (§4) remains the planned search index —
neither is in scope for this ADR.

The deciding factor is the write path's correctness requirement: Postgres's
row-level locking (`FOR UPDATE` / `FOR UPDATE NOWAIT`) and native array/
constraint support map directly onto the seat-hold state machine and the
`bookings` schema design.md already specifies, with no compromise needed to
fit the data model.

## Consequences
- Repositories/entities are built against Spring Data JPA with the Postgres
  dialect (already reflected in `@DataJpaTest` +
  `@AutoConfigureTestDatabase(replace = Replace.NONE)` running against a
  real local Postgres instance rather than an in-memory substitute).
- Horizontal write scaling (sharding by `event_id`, per design.md §7) is
  deferred until it's actually needed; Postgres extensions (e.g. Citus) or
  manual sharding are the fallback path if that day comes.
- `bookings.ticket_ids` can use a native Postgres array column if kept as-is,
  or be replaced by a `@OneToMany` join table — either way, Postgres
  supports both approaches, so nothing here forecloses that later decision.
