package com.ticketmaster.queue;

import com.ticketmaster.event.Event;
import com.ticketmaster.event.EventRepository;
import com.ticketmaster.event.EventStatus;
import com.ticketmaster.event.exception.EventNotOnSaleException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "queue.admit-rate=2",
        "queue.admit-interval-ms=3600000",
        // this suite exercises queue mechanics, not the rate limiter; keep the limit high enough
        // that repeated enqueues from one (event, ip) never trip it. rate limiting is covered in
        // QueueServiceRateLimitTest.
        "queue.enqueue-limit=1000",
        "queue.enqueue-window-ms=3600000",
        "booking.expiry-sweep-interval-ms=3600000"
})
class QueueServiceTest {

    // enqueue() is now per-(event, ip) rate limited; a fixed ip is fine because uniqueEventId()
    // gives every test its own event, so the rate-limit key never collides across tests.
    private static final String IP = "10.0.0.1";

    @Autowired
    private QueueService queueService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private EventRepository eventRepository;

    // enqueue() now gates on event status, so tests need a real ON_SALE event. The DB-assigned
    // id is inherently unique, which also keeps each test's Redis keys from colliding.
    private Long uniqueEventId() {
        Event event = new Event();
        event.setStatus(EventStatus.ON_SALE);
        return eventRepository.save(event)
                              .getId();
    }

    @Test
    void enqueueGrantsImmediateAccessUpToAdmitRateWhenBacklogEmpty() {
        Long eventId = uniqueEventId();

        String first = queueService.enqueue(eventId, IP);
        String second = queueService.enqueue(eventId, IP);

        assertThat(queueService.hasAccess(eventId, first)).isTrue();
        assertThat(queueService.hasAccess(eventId, second)).isTrue();
        assertThat(queueService.getPosition(eventId, first)).isNull();
        assertThat(queueService.getPosition(eventId, second)).isNull();
    }

    @Test
    void enqueueQueuesArrivalsBeyondAdmitRateWhenBacklogEmpty() {
        Long eventId = uniqueEventId();
        queueService.enqueue(eventId, IP); // consumes escape-hatch slot 1
        queueService.enqueue(eventId, IP); // consumes escape-hatch slot 2 (admit-rate=2)

        String overflow = queueService.enqueue(eventId, IP);

        assertThat(queueService.hasAccess(eventId, overflow)).isFalse();
        assertThat(queueService.getPosition(eventId, overflow)).isEqualTo(0);
    }

    @Test
    void enqueueAssignsFifoPositionsInOrderAmongQueuedArrivals() {
        Long eventId = uniqueEventId();
        queueService.enqueue(eventId, IP);
        queueService.enqueue(eventId, IP); // both consume the admit-rate=2 escape hatch

        String first = queueService.enqueue(eventId, IP);
        String second = queueService.enqueue(eventId, IP);
        String third = queueService.enqueue(eventId, IP);

        assertThat(queueService.getPosition(eventId, first)).isEqualTo(0);
        assertThat(queueService.getPosition(eventId, second)).isEqualTo(1);
        assertThat(queueService.getPosition(eventId, third)).isEqualTo(2);
    }

    // arrivals that land on a non-empty backlog must not touch the admitted-count window: they
    // can't be fast-tracked, so a slot they burn (or a window they open) is a slot denied to
    // whoever arrives after the backlog drains. reaching into Redis directly is the only way to
    // pin this - the window's lapse is otherwise only observable via a real admit-interval-ms wait.
    @Test
    void queuedArrivalsDoNotConsumeTheFastTrackWindow() {
        Long eventId = uniqueEventId();
        String admittedCountKey = "queue:" + eventId + ":admitted-count";

        queueService.enqueue(eventId, IP);
        queueService.enqueue(eventId, IP); // consume the admit-rate=2 escape hatch
        queueService.enqueue(eventId, IP); // still finds an empty backlog, but is over rate -> queued

        // simulate the counter's TTL window lapsing while a backlog is still draining
        stringRedisTemplate.delete(admittedCountKey);

        queueService.enqueue(eventId, IP);
        queueService.enqueue(eventId, IP);
        queueService.enqueue(eventId, IP); // all three land on a backlog, so none should re-open the window

        assertThat(stringRedisTemplate.opsForValue().get(admittedCountKey)).isNull();

        queueService.admit();
        queueService.admit(); // drain the 4-deep backlog at admit-rate=2
        assertThat(queueService.getPosition(eventId, "any")).isNull();

        // with the window untouched, the next arrival to find an empty queue gets the escape hatch
        String afterDrain = queueService.enqueue(eventId, IP);

        assertThat(queueService.hasAccess(eventId, afterDrain)).isTrue();
        assertThat(queueService.getPosition(eventId, afterDrain)).isNull();
    }

    @Test
    void enqueueRejectsAnEventThatIsNotOnSale() {
        Event event = new Event();
        event.setStatus(EventStatus.CANCELLED);
        Long cancelledEventId = eventRepository.save(event)
                                               .getId();

        assertThatThrownBy(() -> queueService.enqueue(cancelledEventId, IP))
                .isInstanceOf(EventNotOnSaleException.class);
        // and no queue state was created for it
        assertThat(stringRedisTemplate.opsForSet()
                                      .isMember("queue:active-events", cancelledEventId.toString())).isFalse();
    }

    @Test
    void enqueueRejectsAnUnknownEvent() {
        assertThatThrownBy(() -> queueService.enqueue(999_999_999L, IP))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void getPositionReturnsNullForUnknownToken() {
        Long eventId = uniqueEventId();

        assertThat(queueService.getPosition(eventId, "never-enqueued")).isNull();
    }

    @Test
    void hasAccessReturnsFalseForNullOrBlankToken() {
        Long eventId = uniqueEventId();
        assertThat(queueService.hasAccess(eventId, null)).isFalse();
        assertThat(queueService.hasAccess(eventId, "")).isFalse();
        assertThat(queueService.hasAccess(eventId, "   ")).isFalse();
    }

    @Test
    void hasAccessReturnsFalseWhenNeverAdmitted() {
        Long eventId = uniqueEventId();
        assertThat(queueService.hasAccess(eventId, "never-admitted-token")).isFalse();
    }

    @Test
    void hasAccessIsScopedPerEvent() {
        Long eventA = uniqueEventId();
        Long eventB = uniqueEventId();

        String token = queueService.enqueue(eventA, IP); // backlog empty, under admit-rate -> fast-tracked

        assertThat(queueService.hasAccess(eventA, token)).isTrue();
        assertThat(queueService.hasAccess(eventB, token)).isFalse();
    }

    @Test
    void admitGrantsAccessUpToConfiguredRateFromBacklogInFifoOrder() {
        Long eventId = uniqueEventId();
        queueService.enqueue(eventId, IP);
        queueService.enqueue(eventId, IP); // consume the admit-rate=2 escape hatch

        String first = queueService.enqueue(eventId, IP);
        String second = queueService.enqueue(eventId, IP);
        String third = queueService.enqueue(eventId, IP);

        queueService.admit(); // pops 2 of the 3 queued (admit-rate=2)

        assertThat(queueService.hasAccess(eventId, first)).isTrue();
        assertThat(queueService.hasAccess(eventId, second)).isTrue();
        assertThat(queueService.hasAccess(eventId, third)).isFalse();
        assertThat(queueService.getPosition(eventId, third)).isEqualTo(0);
    }

    @Test
    void admitAdmitsIndependentlyAcrossMultipleActiveEvents() {
        Long eventA = uniqueEventId();
        Long eventB = uniqueEventId();
        queueService.enqueue(eventA, IP);
        queueService.enqueue(eventA, IP); // consume eventA's escape hatch
        queueService.enqueue(eventB, IP);
        queueService.enqueue(eventB, IP); // consume eventB's escape hatch

        String queuedA = queueService.enqueue(eventA, IP);
        String queuedB = queueService.enqueue(eventB, IP);

        queueService.admit();

        assertThat(queueService.hasAccess(eventA, queuedA)).isTrue();
        assertThat(queueService.hasAccess(eventB, queuedB)).isTrue();
    }

    @Test
    void purgeEventRemovesAllQueueState() {
        Long eventId = uniqueEventId();
        String q = "queue:" + eventId;

        String fastTracked = queueService.enqueue(eventId, IP); // escape-hatch grant -> access key
        queueService.enqueue(eventId, IP);                      // consume the admit-rate=2 hatch
        queueService.enqueue(eventId, IP);                      // queued -> backlog + seq + active-events
        queueService.enqueue(eventId, IP);

        // sanity: state is actually present before we purge
        assertThat(stringRedisTemplate.opsForZSet().zCard(q)).isGreaterThan(0);
        assertThat(stringRedisTemplate.opsForSet().isMember("queue:active-events", eventId.toString())).isTrue();

        queueService.purgeEvent(eventId);

        assertThat(stringRedisTemplate.opsForZSet().zCard(q)).isEqualTo(0);
        assertThat(stringRedisTemplate.opsForSet().isMember("queue:active-events", eventId.toString())).isFalse();
        assertThat(stringRedisTemplate.hasKey(q + ":seq")).isFalse();
        assertThat(stringRedisTemplate.hasKey(q + ":admitted-count")).isFalse();
        assertThat(stringRedisTemplate.keys("access:" + eventId + ":*")).isEmpty();
        assertThat(queueService.hasAccess(eventId, fastTracked)).isFalse();
    }

    @Test
    void checkStatusReflectsWaitingAdmittedAndInvalid() {
        Long eventId = uniqueEventId();
        String escapeHatchToken = queueService.enqueue(eventId, IP);
        queueService.enqueue(eventId, IP); // consume the admit-rate=2 escape hatch

        queueService.enqueue(eventId, IP); // queued, will be popped by admit()
        queueService.enqueue(eventId, IP); // queued, will be popped by admit()
        String waitingToken = queueService.enqueue(eventId, IP); // queued, remains waiting

        queueService.admit(); // pops 2 of the 3 queued

        assertThat(queueService.checkStatus(eventId, escapeHatchToken).getQueueStatus())
                .isEqualTo(QueueStatus.ADMITTED);
        assertThat(queueService.checkStatus(eventId, waitingToken).getQueueStatus())
                .isEqualTo(QueueStatus.WAITING);
        assertThat(queueService.checkStatus(eventId, waitingToken).getPosition())
                .isEqualTo(0);
        assertThat(queueService.checkStatus(eventId, "bogus-token").getQueueStatus())
                .isEqualTo(QueueStatus.INVALID);
    }
}
