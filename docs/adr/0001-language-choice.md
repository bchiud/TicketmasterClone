    # ADR 0001: Language & Framework Choice — Java (Spring Boot)

## Status
Accepted — 2026-07-12

## Context
This project implements the ticket-booking system described in
[`design.md`](../design.md): a
read-heavy browse/search path plus a write path that must guarantee a seat is
never sold twice under high concurrency (see design.md §5–§9 — the seat-hold
state machine and row-locking/Redis-lock approaches).

That write path is the crux of the language decision. It needs:
- A mature, well-understood story for **transactional integrity** against a
  relational DB (`SELECT ... FOR UPDATE`, pessimistic/optimistic locking).
- Solid **concurrency primitives**, since the hard problem (Taylor-Swift-style
  on-sale spikes) is fundamentally about correctly serializing contended
  writes.
- A **mature ecosystem** for the surrounding pieces the design calls for:
  ORM/JPA, connection pooling, Redis clients, Elasticsearch clients, testing
  frameworks.
- A **secondary but real goal**: this project doubles as the author's
  hands-on introduction to Spring Boot and the JVM ecosystem, which are
  widely used in industry for exactly this class of system (transactional,
  service-oriented backends).

## Options Considered

### Java (Spring Boot)
- **For**:
  - Best-in-class JPA/Hibernate + `@Transactional` support maps directly onto
    the row-locking pattern in design.md §9.2.
  - Mature JDBC connection pooling (HikariCP), first-class
    Postgres/Redis/Elasticsearch clients.
  - Strong static typing catches a large class of bugs at compile time, which
    matters when the core requirement is "never double-sell a seat."
  - Huge ecosystem and hiring pool for this exact workload (transactional
    e-commerce/booking backends).
  - Spring Boot's opinionated structure
    (`@Entity`/`@Repository`/`@Service`/`@RestController`) is a good fit for
    learning idiomatic layered architecture.
- **Against**:
  - More boilerplate than newer languages/frameworks.
  - JVM startup time and memory footprint are heavier than Go for something like
    a small Redis-hold worker.
  - Verbose compared to Kotlin/TypeScript for the same logic.

### Go
- **For**:
  - Excellent concurrency primitives (goroutines/channels) map well onto the
    hold-worker/queue pieces of the design.
  - Fast startup, low memory footprint, single static binary — good for the
    horizontally-scaled stateless services in §3.
  - Simpler language surface.
- **Against**:
  - Weaker ORM story (most teams hand-roll SQL or use a thin query builder),
    which raises the risk of getting the locking transaction subtly wrong.
  - Smaller ecosystem for the "boring but necessary" plumbing (JPA-style entity
    mapping, migrations, validation).
  - Less relevant to the author's immediate learning goal.

### Node.js / TypeScript
- **For**:
  - Fast to prototype.
  - One language across a future frontend and backend.
  - Large package ecosystem.
  - Good async I/O for the read-heavy browse path.
- **Against**:
  - Single-threaded event loop means CPU-bound work and long-held DB
    transactions can block the process; the write path here is exactly the kind
    of contended, transaction-heavy workload where this is a liability.
  - Weaker type-level guarantees than Java unless disciplined about strict
    TypeScript.
  - Transactional ORMs (Prisma/TypeORM) are less battle-tested for pessimistic
    locking than JPA/Hibernate.

### Python
- **For**:
  - Very fast to write.
  - Django/FastAPI + SQLAlchemy are productive.
  - Huge ecosystem.
  - Easy to learn.
- **Against**:
  - GIL limits true parallelism for CPU-bound work within a process (mitigated
    by process-based scaling, but adds ops complexity).
  - Dynamic typing raises the risk of runtime bugs in the money/seat-locking
    code path, which is the one place in this system where correctness bugs are
    most costly.
  - Generally weaker raw throughput per instance than JVM/Go for a service that
    will be horizontally scaled anyway.

### Kotlin (JVM)
- **For**:
  - Runs on the same JVM/Spring ecosystem as Java — same libraries, same
    transactional guarantees — with less boilerplate and null-safety built into
    the type system.
  - Because it shares the JVM/Spring runtime, switching to it later would be
    low-risk if the project's goal ever shifted from "learn Spring Boot" to
    "build this as concisely as possible."
- **Against**:
  - Smaller community/resource pool than plain Java for a learning project; most
    Spring Boot tutorials, Stack Overflow answers, and interview-relevant
    material default to Java, which matters given the explicit learning goal here.
  - For the *current* goal, Java's larger body of mainstream Spring Boot
    documentation outweighs Kotlin's conciseness/null-safety advantages.

## Decision
Use **Java with Spring Boot** for the application, backed by Postgres (JPA/
Hibernate) for the transactional core described in design.md §7–§9, with room
to add Redis for the hold/lock layer later.

The deciding factors: the write path's correctness requirements are best
served by a statically-typed language with a mature transactional-ORM story,
and Java/Spring Boot is the most direct, best-documented path to both that
technical fit and the author's goal of learning the JVM/Spring ecosystem that
underlies a large share of production booking/e-commerce systems.

## Consequences
- More verbose code than Go/Kotlin/TypeScript for equivalent logic; accepted
  as a reasonable cost given the learning goal and the ecosystem payoff.
- JVM memory/startup overhead is acceptable at this project's scale (a
  learning project, not a production fleet); would be revisited if a
  lightweight standalone hold-worker process were ever split out (Go would be
  the natural choice there).
- Locks in the JPA/Hibernate + Spring Data ecosystem for repositories,
  `@Transactional` for the locking transactions in §9.2, and Spring MVC for
  the REST layer — all decisions downstream of this one.
- Java vs. Kotlin is a low-risk choice to leave open: since both share the
  JVM/Spring runtime, this decision can be revisited without disturbing the
  transactional-DB/ORM choices above if the project's priorities shift.
