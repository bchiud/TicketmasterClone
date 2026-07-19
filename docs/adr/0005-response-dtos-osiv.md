# ADR 0005: API Response DTOs & Disabling Open-Session-in-View

## Status
Accepted — 2026-07-17

## Context
Controllers returned JPA entities directly (`Event`, `Booking`, `Ticket`,
`Payment`, `Seat`, `Venue`). Two latent problems compounded:

1. **Serialization cycle.** The Booking–Ticket association from
   [ADR-0003](0003-booking-ticket-association.md) is bidirectional
   (`Booking.tickets` ⇄ `Ticket.booking`) with no `@JsonIgnore`. Jackson walks
   both directions, so serializing a `Booking` that has tickets recurses
   `Booking → tickets → booking → …` until Jackson's max nesting depth (500),
   throwing `HttpMessageNotWritableException` — but only *after* HTTP 200 and
   ~500 levels of nested JSON have already been flushed (`response committed
   already`). The client receives a broken, truncated `200`. Every endpoint
   returning a `Booking` with tickets was affected, plus anything embedding a
   Booking transitively (`Payment.booking`, `Ticket.booking`).

2. **Open-Session-in-View (OSIV).** Spring Boot enables
   `spring.jpa.open-in-view=true` by default, keeping the Hibernate session open
   through view rendering. This let lazy associations (`Booking.tickets`) load
   *during JSON serialization*, outside any transaction — masking N+1 queries
   and holding a pooled DB connection for the whole request, including slow
   serialization.

Returning entities also welds the wire contract to the schema (any field or
relationship change leaks to clients) and leaves no place to shape per-endpoint
responses.

## Options Considered

### Break the cycle with `@JsonIgnore` / `@JsonManagedReference`
- **For**: One annotation; smallest possible change.
- **Against**:
  - Still serializes entities — the wire format stays welded to the schema.
  - Still relies on OSIV; the hidden N+1 and connection-hold remain.
  - Suppresses a field globally on the entity, which a different endpoint might
    legitimately need — it's not a per-response decision.

### Response DTOs, keep OSIV on
- **For**:
  - Fixes the cycle — DTOs carry no back-reference.
  - Decouples the wire format from the schema.
- **Against**:
  - Leaves OSIV's connection-held-through-render and silent lazy-loading in
    place, so the N+1 and pool-hold problems persist.
  - Lazy access during mapping still "just works," hiding *where* fetching
    happens instead of making it deliberate.

### Response DTOs + disable OSIV (chosen)
`spring.jpa.open-in-view=false`, and each resource gets a response `record`
(`BookingResponse`, `TicketSummary`, `TicketResponse`, `PaymentResponse`,
`EventResponse`, `SeatResponse`, `VenueResponse`) with `@ManyToOne` associations
flattened to ids and no entity back-references, mapped via a static
`from(entity)`.
- **For**:
  - The DTO graph has no back-reference, so the serialization cycle is
    *structurally unrepresentable* — not merely suppressed by an annotation.
  - Decouples the wire contract from the schema; each endpoint returns exactly
    the fields it intends.
  - OSIV-off makes any lazy access outside a transaction fail loudly
    (`LazyInitializationException`), surfacing hidden fetches instead of masking
    them — this directly exposed five previously-broken endpoints.
  - The DB connection is released at transaction commit, not at end-of-request.
- **Against**:
  - More code: a DTO per resource plus its mapping.
  - Fetching must be explicit — a lazy collection needed for a response requires
    an `@EntityGraph`/join fetch or in-transaction access; forget one and the
    endpoint throws rather than silently N+1-ing.

## Decision
Introduce a response DTO (`record`) per resource, mapped from the entity via a
static `from(...)`, with `@ManyToOne` associations flattened to ids (`venueId`,
`bookingId`, …) and **no** entity back-references. Controllers return DTOs only —
no endpoint serializes a JPA entity. Set `spring.jpa.open-in-view=false`. Where a
response needs a lazy collection, fetch it deliberately: `BookingRepository`'s
`findWithTicketsById` / `findWithTicketsByUserId` use
`@EntityGraph(attributePaths = "tickets")`, and the hold/pay/cancel paths
populate tickets inside their `@Transactional` service methods. Paginated results
map with `Page.map(EventResponse::from)`, which preserves the page metadata.

Deciding factors: this is the only option that makes the cycle *unrepresentable*
**and** removes the OSIV crutch, and it establishes a deliberate, visible fetch
boundary consistent with fetching inside the transaction.

## Consequences
- No controller returns a JPA entity; the wire contract is the set of `*Response`
  records. A schema change no longer leaks to clients unless a DTO is changed too.
- The bidirectional Booking–Ticket cycle ([ADR-0003](0003-booking-ticket-association.md))
  can no longer form: `TicketSummary` has no `booking` field. A regression test
  asserts `$.tickets[0].booking` does not exist.
- OSIV-off surfaced five endpoints that were silently relying on
  lazy-load-during-serialization — `GET /payments/{id}`,
  `GET /bookings/{id}/payments`, `GET /tickets/{id}`,
  `GET /events/{eventId}/tickets`, and the raw-`Booking` `POST /bookings/{id}/refund`.
  All now map to DTOs and were verified `200` on the real request path (a full
  `@SpringBootTest` with the real session, not a mocked slice).
- A lazy collection needed for a response must be fetched deliberately
  (`@EntityGraph` or in-transaction); a missing fetch now fails loudly instead of
  N+1-ing during serialization.
- `TicketResponse.bookingId` is `null` for AVAILABLE tickets (which have no
  booking); `from()` guards the null.
- DTO `from()` methods reference only eager associations or an eagerly-fetched
  collection, so mapping is safe once the transaction/session has closed.
- `EventResponse` / `SeatResponse` / `VenueResponse` flatten `venue → venueId`; a
  nested `VenueSummary(id, name, city)` can be added later if a client needs venue
  detail in the same call.
- Controller tests now build complete fixtures (associations populated).
  Previously they passed with bare entities because the mocked repositories never
  triggered the lazy load — a false green that hid the real bug; the DTO mapping
  forces realistic fixtures.
- Turning OSIV off is only clean *because* the DTOs assemble data at a deliberate
  fetch point; the two changes are two halves of one decision.
