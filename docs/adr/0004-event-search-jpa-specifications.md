# ADR 0004: Dynamic Event Search — JPA Specifications

## Status
Accepted — 2026-07-15

## Context
[`design.md`](../design.md) §1/§4 calls for browsing and searching events by
**name, city, date, and artist/performer**, on top of the existing `status`
filter. These are all *optional* filters that combine with AND: a request may
supply any subset (including none), and the result is events matching all of
the supplied ones.

The starting implementation used Spring Data **derived query methods** with an
if/else per combination:

```java
if (name == null && status == null) return findAll();
else if (name == null)              return findByStatus(status);
else if (status == null)            return findByNameContainingIgnoreCase(name);
else                                return findByNameContainingIgnoreCaseAndStatus(name, status);
```

That is already 2² = 4 branches and 4 repository methods for just 2 filters.
The design's full set is up to **six** optional filters (name, status, city,
performer, and a `from`/`to` start-time range), which as derived methods is a
combinatorial explosion — dozens of methods and an unreadable branch tree. Two
additional wrinkles: **city lives on `Venue`, not `Event`**, so it needs a join
across the `@ManyToOne`, and the date range needs `>=`/`<=` predicates on
`startsAt`.

Scope note: this is **structured attribute filtering**, not relevance/full-text
search. Elasticsearch (design.md §4) remains the planned option for the latter
and is out of scope here — at this project's scale, Postgres `WHERE` clauses
cover attribute filtering directly.

## Options Considered

### Derived query methods (status quo)
- **For**:
  - Zero query code.
  - Method names are type-checked.
  - Trivial for one or two filters.
- **Against**:
  - Does not compose.
  - Every new optional filter multiplies the method count and branch tree (2ⁿ).
  - Already visibly straining at two filters; unmaintainable at six.

### Single dynamic `@Query` with null-guards
A JPQL query of the form `WHERE (:name IS NULL OR lower(e.name) LIKE ...) AND (:status IS NULL OR e.status = :status) AND ...`.
- **For**:
  - One method, one place.
  - No new dependency.
- **Against**:
  - The JPQL grows long and repetitive.
  - The join for `city` and the `lower(...)` wrapping make it verbose.
  - The all-null case is awkward.
  - Field references are strings in JPQL (no compile-time checking).
  - Extending it means editing one increasingly gnarly query.

### JPA Specifications (Criteria API) — chosen
`EventRepository extends JpaSpecificationExecutor<Event>`, plus an
`EventSpecifications.matching(...)` builder that appends one `Predicate` per
non-null parameter and ANDs them.
- **For**:
  - Built into Spring Data JPA — **no new dependency**.
  - Each optional filter is an independently composed predicate added only when
    its param is present, so there is no branch explosion.
  - AND-composition is natural (`cb.and(predicates)`); an empty predicate list is
    a no-op that returns all events, replacing the old `findAll()` branch for
    free.
  - Handles the `Event → Venue` join (`root.join("venue").get("city")`) and the
    `startsAt` range predicates cleanly.
  - Composes directly with `Pageable`/`Sort`, which sets up pagination as a later
    step.
- **Against**:
  - The Criteria API is verbose and lambda-heavy.
  - Entity field names are **string literals** (`"name"`, `"venue"`,
    `"startsAt"`) that are *not* compile-checked — a typo is a runtime error, not
    a build error, unless the JPA static metamodel is added.

### Querydsl
- **For**:
  - Fluent and fully type-safe via generated `Q`-classes.
  - Composes predicates well.
- **Against**:
  - Adds a dependency plus an annotation-processing/codegen build step.
  - Overkill for six filters on a single entity in a learning project, and
    another tool to learn for a marginal gain over Specifications here.

## Decision
Use **JPA Specifications**. `EventRepository` mixes in
`JpaSpecificationExecutor<Event>`; `EventSpecifications.matching(name, status,
city, performer, from, to)` builds a `Specification<Event>` by appending one
predicate per non-null argument (case-insensitive `LIKE` for name/performer,
equality for status, a `venue` join for city, and inclusive `>=`/`<=` bounds on
`startsAt`), ANDed together. `EventController#getAllEvents` takes the six as
optional `@RequestParam`s and calls `findAll(spec)`.

The deciding factors: it is the idiomatic Spring Data answer for *dynamic
optional* filters, needs no new dependency, composes without a branch
explosion, natively handles the cross-entity join and the range predicate, and
leaves a clean path to pagination.

## Consequences
- `EventRepository` extends `JpaSpecificationExecutor<Event>`; the existing
  derived methods are retained (still used by the on-sale/expiry sweeps).
- Field names in the Criteria API are unchecked strings, so a typo surfaces at
  runtime. This is mitigated by `EventSpecificationsTest`, which exercises
  **every** predicate (name substring/case-insensitivity, the city join,
  performer, status, inclusive date bounds, range, and AND-combination) against
  a real Postgres instance. Adding the JPA static metamodel later would restore
  compile-time safety if desired.
- Pagination and sorting (`findAll(spec, pageable)`) are a natural next step and
  are deferred; the endpoint currently returns an unbounded list.
- The `idx_events_starts_at` index (added alongside this work) supports the
  `from`/`to` range predicate.
- Relevance/full-text search (design.md §4, Elasticsearch) stays out of scope;
  if it is ever needed it would be a separate index/service, not an extension of
  this specification.
