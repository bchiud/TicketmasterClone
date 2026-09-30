# /// script
# requires-python = ">=3.11"
# dependencies = ["httpx>=0.27"]
# ///
"""
Load test for the Ticketmaster clone: on-sale spike, per-IP rate limiting, hot-seat contention.

Standalone - no project dependencies (httpx is pulled in by uv, not the pom). Run with uv (see loadtest/README.md):

    uv run loadtest/loadtest.py spike     --buyers 1000 --seats 200
    uv run loadtest/loadtest.py ratelimit --abuser-requests 50 --normal-users 50
    uv run loadtest/loadtest.py hotseat   --rounds 50 --contenders 100

Every phase creates its own venue/event/users, so runs never interfere with each other.
Seats and users have no write API, so they are inserted with `psql` (the rest goes through HTTP).
Each simulated buyer sends a distinct `X-Forwarded-For` IP; the app only honors it under the
`prod` profile (server.forward-headers-strategy=FRAMEWORK) - see README.md.
"""
import argparse
import asyncio
import json
import random
import ssl
import statistics
import subprocess
import sys
import time
import uuid
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

import httpx

RESULTS_DIR = Path(__file__).parent / "results"


# ---------------------------------------------------------------------------------------------
# metrics
# ---------------------------------------------------------------------------------------------
class Metrics:
    def __init__(self):
        self.samples = defaultdict(list)  # endpoint -> [(t_end, latency_ms, status_or_err)]
        self.hold_outcomes = Counter()  # classified hold failures
        self.notes = Counter()  # misc counters (invalid polls, give-ups, ...)

    def record(self, endpoint, t_end, latency_ms, status):
        self.samples[endpoint].append((t_end, latency_ms, status))

    def summary(self, t0, t1):
        out = {}
        all_ends = []
        for ep, rows in sorted(self.samples.items()):
            lat = sorted(r[1] for r in rows)
            codes = Counter(str(r[2]) for r in rows)
            out[ep] = {
                "count": len(rows),
                "p50_ms": pct(lat, 50),
                "p95_ms": pct(lat, 95),
                "p99_ms": pct(lat, 99),
                "max_ms": round(lat[-1], 1) if lat else None,
                "status": dict(sorted(codes.items())),
            }
            all_ends.extend(r[0] for r in rows)
        total = sum(len(r) for r in self.samples.values())
        dur = t1 - t0
        per_sec = Counter(int(t - t0) for t in all_ends)
        busy = [per_sec[s] for s in range(int(dur) + 1)]
        return {
            "total_requests": total,
            "duration_s": round(dur, 2),
            "avg_rps": round(total / dur, 1) if dur else None,
            "peak_1s_rps": max(busy) if busy else 0,
            "median_1s_rps": statistics.median(busy) if busy else 0,
            "rps_timeline": busy,
            "endpoints": out,
        }


def pct(sorted_vals, p):
    if not sorted_vals:
        return None
    k = max(0, min(len(sorted_vals) - 1, int(round(p / 100 * len(sorted_vals) + 0.5)) - 1))
    return round(sorted_vals[k], 1)


async def call(session, metrics, endpoint, method, url, **kw):
    """Issue one request; returns (status, body_text). Connection errors are recorded as 'ERR:<type>'."""
    t = time.perf_counter()
    try:
        r = await session.request(method, url, **kw)
        body, status = r.text, r.status_code
    except Exception as e:  # noqa: BLE001 - we want every client-side failure counted, not raised
        status, body = f"ERR:{type(e).__name__}", ""
    end = time.perf_counter()
    metrics.record(endpoint, end, (end - t) * 1000, status)
    return status, body


def classify_409(body):
    b = body.lower()
    if "modified concurrently" in b:
        return "409_lock_conflict(NOWAIT loser)"
    if "unavailable" in b:
        return "409_ticket_unavailable(already held)"
    if "not on sale" in b:
        return "409_event_not_on_sale(sold out)"
    if "maximum number of tickets" in b:
        return "409_ticket_cap"
    return "409_other"


# ---------------------------------------------------------------------------------------------
# fixtures (HTTP where an API exists, psql where it doesn't)
# ---------------------------------------------------------------------------------------------
def psql(db, sql):
    r = subprocess.run(["psql", "-X", "-q", "-d", db, "-At", "-v", "ON_ERROR_STOP=1", "-c", sql],
                       capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"psql failed: {r.stderr}")
    return r.stdout.strip()


async def make_event(session, args, run_id, seats, requires_queue, label):
    r = await session.post(f"{args.base}/venues", json={
        "name": f"{label} venue [loadtest {run_id}]", "address": "1 Load St", "city": "Benchtown"})
    assert r.status_code == 201, f"create venue -> {r.status_code} {r.text}"
    venue_id = r.json()["id"]
    psql(args.db, f"INSERT INTO seats (venue_id, section, row_label, seat_number) "
                  f"SELECT {venue_id}, 'GA', 'A', n::text FROM generate_series(1, {seats}) n")
    r = await session.post(f"{args.base}/events", json={
        "name": f"{label} [loadtest {run_id}]", "performer": "Load Test", "venueId": venue_id,
        "startsAt": "2030-01-01T20:00:00Z", "onSaleAt": "2020-01-01T00:00:00Z",
        "priceCents": 5000, "requiresQueue": requires_queue})
    assert r.status_code == 201, f"create event -> {r.status_code} {r.text}"
    ev = r.json()
    assert ev["status"] == "ON_SALE", ev
    r = await session.get(f"{args.base}/events/{ev['id']}/tickets")
    ticket_ids = [t["id"] for t in r.json()]
    assert len(ticket_ids) == seats
    return ev["id"], ticket_ids


def make_users(db, run_id, n):
    tag = f"lt-{run_id}"
    psql(db, f"INSERT INTO users (email, name) SELECT '{tag}-' || n || '@loadtest', '{tag}' "
             f"FROM generate_series(1, {n}) n")
    ids = psql(db, f"SELECT id FROM users WHERE name = '{tag}' ORDER BY id").split()
    return [int(i) for i in ids]


def client(timeout_s, ssl_ctx=None):
    # no client-side connection cap
    return httpx.AsyncClient(timeout=timeout_s, verify=ssl_ctx or True,
                             limits=httpx.Limits(max_connections=None, max_keepalive_connections=None))


def ip_for(i):
    i += 1
    return f"10.{(i >> 16) & 255}.{(i >> 8) & 255}.{i & 255}"


# ---------------------------------------------------------------------------------------------
# phase 1: on-sale spike
# ---------------------------------------------------------------------------------------------
async def buyer(i, session, m, args, event_id, user_id, all_tickets, start_evt, results):
    base, xff = args.base, {"X-Forwarded-For": ip_for(i)}
    await start_evt.wait()
    t_arrive = time.perf_counter()

    st, token = await call(session, m, "POST /events/{id}/queue", "POST", f"{base}/events/{event_id}/queue", headers=xff)
    if st != 200:
        m.notes[f"gave_up_enqueue_{st}"] += 1
        return

    # poll the waiting room until admitted
    invalid_seen = 0
    deadline = time.perf_counter() + args.queue_timeout
    while True:
        st, body = await call(session, m, "GET /events/{id}/queue/{token}", "GET",
                              f"{base}/events/{event_id}/queue/{token}")
        state = json.loads(body).get("queueStatus") if st == 200 else None
        if state == "ADMITTED":
            break
        if state == "INVALID":
            # A token that was just ZPOPMIN'ed but whose access key isn't SET yet reads INVALID
            # (see README "findings"). A real client would stop here; we count it and keep polling.
            invalid_seen += 1
            m.notes["poll_returned_INVALID"] += 1
        if time.perf_counter() > deadline:
            m.notes["gave_up_queue_timeout"] += 1
            return
        await asyncio.sleep(args.poll_interval)
    if invalid_seen:
        m.notes["buyers_seeing_transient_INVALID_then_ADMITTED"] += 1
    results["admit_wait_s"].append(time.perf_counter() - t_arrive)
    if args.queue_only:
        return

    # pick a seat from the (page-load) seat map; on conflict refresh the map and pick again
    candidates = list(all_tickets)
    for attempt in range(args.max_attempts):
        if not candidates:
            m.notes["gave_up_no_seats_left"] += 1
            return
        tid = random.choice(candidates)
        st, body = await call(session, m, "POST /bookings/hold", "POST", f"{base}/bookings/hold", json={
            "userId": user_id, "eventId": event_id, "ticketIds": [tid],
            "idempotencyKey": str(uuid.uuid4()), "accessToken": token})
        if st == 200:
            booking = json.loads(body)
            results["held_ticket_ids"].extend(t["id"] for t in booking["tickets"])
            st, body = await call(session, m, "POST /bookings/{id}/pay", "POST", f"{base}/bookings/{booking['id']}/pay")
            if st == 200:
                paid = json.loads(body)
                if paid["status"] == "CONFIRMED":
                    results["confirmed_ticket_ids"].extend(t["id"] for t in paid["tickets"])
                    results["confirmed_booking_ids"].append(paid["id"])
            return
        if st == 409:
            kind = classify_409(body)
            m.hold_outcomes[kind] += 1
            if "not_on_sale" in kind:
                m.notes["gave_up_sold_out"] += 1
                return
            st, body = await call(session, m, "GET /events/{id}/tickets?status=AVAILABLE", "GET",
                                  f"{base}/events/{event_id}/tickets", params={"status": "AVAILABLE"})
            candidates = [t["id"] for t in json.loads(body)] if st == 200 else [c for c in candidates if c != tid]
            continue
        m.hold_outcomes[f"{st}"] += 1
        m.notes[f"gave_up_hold_{st}"] += 1
        return
    m.notes["gave_up_max_attempts"] += 1


def verify_spike(db, event_id):
    q = {
        "ticket_status_counts":
            f"SELECT status || '=' || count(*) FROM tickets WHERE event_id={event_id} GROUP BY status ORDER BY status",
        "inventory": f"SELECT count(*) FROM tickets WHERE event_id={event_id}",
        "confirmed_bookings": f"SELECT count(*) FROM bookings WHERE event_id={event_id} AND status='CONFIRMED'",
        "booked_tickets": f"SELECT count(*) FROM tickets WHERE event_id={event_id} AND status='BOOKED'",
        "succeeded_payments":
            f"SELECT count(*) FROM payments p JOIN bookings b ON b.id=p.booking_id "
            f"WHERE b.event_id={event_id} AND p.status='SUCCEEDED'",
        # a seat sold twice: more than one CONFIRMED booking pointing at the same seat
        "seats_with_gt1_confirmed_booking":
            f"SELECT count(*) FROM (SELECT t.seat_id FROM tickets t JOIN bookings b ON b.id=t.booking_id "
            f"WHERE t.event_id={event_id} AND b.status='CONFIRMED' GROUP BY t.seat_id HAVING count(*)>1) x",
        # tickets.booking_id is a single FK, so a double-sale would show up as a CONFIRMED booking
        # whose ticket was re-pointed at another booking, i.e. a paid booking with no seat
        "confirmed_bookings_without_ticket":
            f"SELECT count(*) FROM bookings b WHERE b.event_id={event_id} AND b.status='CONFIRMED' "
            f"AND NOT EXISTS (SELECT 1 FROM tickets t WHERE t.booking_id=b.id)",
        "confirmed_cents_eq_booked_ticket_cents":
            f"SELECT (SELECT coalesce(sum(total_cents),0) FROM bookings WHERE event_id={event_id} AND status='CONFIRMED')"
            f" = (SELECT coalesce(sum(price_cents),0) FROM tickets WHERE event_id={event_id} AND status='BOOKED')",
        "bookings_with_gt1_succeeded_payment":
            f"SELECT count(*) FROM (SELECT p.booking_id FROM payments p JOIN bookings b ON b.id=p.booking_id "
            f"WHERE b.event_id={event_id} AND p.status='SUCCEEDED' GROUP BY p.booking_id HAVING count(*)>1) x",
        "event_status": f"SELECT status FROM events WHERE id={event_id}",
    }
    return {k: psql(db, v).replace("\n", ", ") for k, v in q.items()}, q


async def run_spike(args):
    run_id = f"{datetime.now():%H%M%S}-{uuid.uuid4().hex[:6]}"
    async with client(args.request_timeout) as setup:
        event_id, tickets = await make_event(setup, args, run_id, args.seats, True, "Spike")
    users = make_users(args.db, run_id, args.buyers)
    print(f"event={event_id} seats={len(tickets)} buyers={len(users)}", flush=True)

    # one client (= one keep-alive TCP connection) per buyer, like one browser per fan
    ssl_ctx = ssl.create_default_context()  # shared: building one per client is slow and unused over http
    clients = [client(args.request_timeout, ssl_ctx) for _ in range(args.buyers)]
    warm = 0
    if not args.cold:
        # Pre-open each buyer's connection (fans sitting on the on-sale page) so the measurement is the
        # app, not the laptop's TCP accept backlog (kern.ipc.somaxconn=128, Tomcat accept-count=100)
        # dropping SYNs from thousands of simultaneous connects. Use --cold to include connects.
        sem = asyncio.Semaphore(100)

        async def warmup(c):
            async with sem:
                r = await c.get(f"{args.base}/events/{event_id}/queue/warmup")
                return r.status_code == 200
        warm = sum(await asyncio.gather(*(warmup(c) for c in clients)))
        print(f"warm keep-alive connections: {warm}", flush=True)

    m, start = Metrics(), asyncio.Event()
    results = defaultdict(list)
    tasks = [asyncio.create_task(buyer(i, clients[i], m, args, event_id, users[i], tickets, start, results))
             for i in range(args.buyers)]
    await asyncio.sleep(0.2)
    t0 = time.perf_counter()
    start.set()  # everyone arrives at once: the on-sale spike
    await asyncio.gather(*tasks)
    t1 = time.perf_counter()
    await asyncio.gather(*(c.aclose() for c in clients))

    db_check, queries = verify_spike(args.db, event_id) if not args.queue_only else ({}, {})
    held, conf = results["held_ticket_ids"], results["confirmed_ticket_ids"]
    aw = sorted(results["admit_wait_s"])
    out = {
        "phase": "spike-queue-only" if args.queue_only else "spike", "event_id": event_id,
        "connections": "cold (connect inside the spike)" if args.cold else f"warm ({warm} pre-opened)", "buyers": args.buyers, "seats": args.seats,
        "metrics": m.summary(t0, t1),
        "hold_409_breakdown": dict(m.hold_outcomes),
        "client_notes": dict(m.notes),
        "admitted_buyers": len(aw),
        "admit_wait_s": {"p50": pct(aw, 50), "p95": pct(aw, 95), "p99": pct(aw, 99), "max": aw[-1] if aw else None},
        "client_double_hold_ticket_ids": sorted(t for t, c in Counter(held).items() if c > 1),
        "client_double_confirm_ticket_ids": sorted(t for t, c in Counter(conf).items() if c > 1),
        "client_successful_holds": len(held),
        "client_confirmed_bookings": len(results["confirmed_booking_ids"]),
        "db_check": db_check,
        "db_queries": queries,
    }
    return out


# ---------------------------------------------------------------------------------------------
# phase 2: per-IP rate limiting
# ---------------------------------------------------------------------------------------------
async def run_ratelimit(args):
    run_id = f"{datetime.now():%H%M%S}-{uuid.uuid4().hex[:6]}"
    async with client(30) as session:
        event_id, _ = await make_event(session, args, run_id, 10, True, "RateLimit")
        m = Metrics()
        abuser_ip = "203.0.113.7"

        async def join(ip, bucket):
            hdr = {"X-Forwarded-For": ip} if ip else {}
            st, _ = await call(session, m, bucket, "POST", f"{args.base}/events/{event_id}/queue", headers=hdr)
            return st

        t0 = time.perf_counter()
        abuser = [join(abuser_ip, "abuser (1 IP)") for _ in range(args.abuser_requests)]
        normal = [join(ip_for(100_000 + i), "normal users (1 IP each)") for i in range(args.normal_users)]
        await asyncio.gather(*abuser, *normal)
        t1 = time.perf_counter()
        burst = m.summary(t0, t1)

        # after the window expires the abuser's bucket resets
        await asyncio.sleep(args.window_s + 0.5)
        after = [await join(abuser_ip, "abuser after window reset") for _ in range(3)]

        # requests with no X-Forwarded-For all collapse onto the socket address (127.0.0.1)
        no_xff = [await join(None, "no X-Forwarded-For (127.0.0.1)") for _ in range(8)]
    after_m = m.summary(t0, t1)["endpoints"]
    burst["endpoints"].update({k: v for k, v in after_m.items() if k not in burst["endpoints"]})
    return {"phase": "ratelimit", "event_id": event_id, "metrics": burst,
            "abuser_after_reset": after, "no_xff_sequence": no_xff}


# ---------------------------------------------------------------------------------------------
# phase 3: hot seat - many buyers, one ticket, repeated
# ---------------------------------------------------------------------------------------------
async def run_hotseat(args):
    run_id = f"{datetime.now():%H%M%S}-{uuid.uuid4().hex[:6]}"
    async with client(60) as session:
        # no queue gate: isolate the DB row lock
        event_id, tickets = await make_event(session, args, run_id, args.rounds, False, "HotSeat")
        # fresh contenders every round so booking.max-tickets-per-user never rejects a contender early
        users = make_users(args.db, run_id, args.contenders * args.rounds)
        m = Metrics()
        winners_per_round, conflict_kinds = [], Counter()
        t0 = time.perf_counter()
        for tid in tickets:
            async def one(u):
                st, body = await call(session, m, "POST /bookings/hold (hot seat)", "POST", f"{args.base}/bookings/hold",
                                      json={"userId": u, "eventId": event_id, "ticketIds": [tid],
                                            "idempotencyKey": str(uuid.uuid4())})
                if st == 409:
                    conflict_kinds[classify_409(body)] += 1
                return st
            k = len(winners_per_round)
            sts = await asyncio.gather(*(one(u) for u in users[k * args.contenders:(k + 1) * args.contenders]))
            winners_per_round.append(sum(1 for s in sts if s == 200))
        t1 = time.perf_counter()

    db = {
        "tickets_held": psql(args.db, f"SELECT count(*) FROM tickets WHERE event_id={event_id} AND status='HELD'"),
        "pending_bookings": psql(args.db, f"SELECT count(*) FROM bookings WHERE event_id={event_id} AND status='PENDING'"),
        "bookings_without_ticket": psql(args.db,
            f"SELECT count(*) FROM bookings b WHERE b.event_id={event_id} "
            f"AND NOT EXISTS (SELECT 1 FROM tickets t WHERE t.booking_id=b.id)"),
    }
    return {"phase": "hotseat", "event_id": event_id, "rounds": args.rounds, "contenders": args.contenders,
            "winners_per_round": Counter(winners_per_round), "rounds_with_exactly_one_winner":
                sum(1 for w in winners_per_round if w == 1),
            "conflict_breakdown": dict(conflict_kinds), "metrics": m.summary(t0, t1), "db_check": db}


# ---------------------------------------------------------------------------------------------
def print_report(res):
    met = res["metrics"]
    print(f"\n=== {res['phase']} ===")
    print(f"requests={met['total_requests']}  duration={met['duration_s']}s  avg_rps={met['avg_rps']}  "
          f"peak_1s_rps={met['peak_1s_rps']}  median_1s_rps={met['median_1s_rps']}")
    print(f"{'endpoint':52} {'count':>6} {'p50':>8} {'p95':>8} {'p99':>8} {'max':>8}  status")
    for ep, e in met["endpoints"].items():
        print(f"{ep:52} {e['count']:>6} {e['p50_ms']:>8} {e['p95_ms']:>8} {e['p99_ms']:>8} {e['max_ms']:>8}  {e['status']}")
    skip = {"metrics", "db_queries", "phase"}
    for k, v in res.items():
        if k not in skip:
            print(f"{k}: {v}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", default="http://localhost:8080")
    p.add_argument("--db", default="ticketmaster_loadtest", help="database the app under test points at")
    sub = p.add_subparsers(dest="phase", required=True)

    s = sub.add_parser("spike")
    s.add_argument("--buyers", type=int, default=1000)
    s.add_argument("--seats", type=int, default=200)
    s.add_argument("--poll-interval", type=float, default=0.5)
    s.add_argument("--queue-timeout", type=float, default=180)
    s.add_argument("--max-attempts", type=int, default=5)
    s.add_argument("--request-timeout", type=float, default=60)
    s.add_argument("--cold", action="store_true",
                   help="don't pre-open connections: every buyer's TCP connect lands inside the spike")
    s.add_argument("--queue-only", action="store_true",
                   help="stop each buyer once ADMITTED (measures the waiting room alone, no booking)")

    r = sub.add_parser("ratelimit")
    r.add_argument("--abuser-requests", type=int, default=50)
    r.add_argument("--normal-users", type=int, default=50)
    r.add_argument("--window-s", type=float, default=10, help="queue.enqueue-window-ms / 1000")

    h = sub.add_parser("hotseat")
    h.add_argument("--rounds", type=int, default=50)
    h.add_argument("--contenders", type=int, default=100)

    args = p.parse_args()
    res = asyncio.run({"spike": run_spike, "ratelimit": run_ratelimit, "hotseat": run_hotseat}[args.phase](args))
    print_report(res)
    RESULTS_DIR.mkdir(exist_ok=True)
    tag = f"-{args.buyers}{'-queue-only' if args.queue_only else ''}" if args.phase == "spike" else ""
    path = RESULTS_DIR / f"{datetime.now():%Y%m%d-%H%M%S}-{args.phase}{tag}-event{res['event_id']}.json"
    path.write_text(json.dumps(res, indent=2, default=str))
    print(f"\nresults -> {path}")


if __name__ == "__main__":
    main()
