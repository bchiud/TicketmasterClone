package com.ticketmaster.queue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "queue.admit-rate=2",
        "queue.admit-interval-ms=3600000",
        "booking.expiry-sweep-interval-ms=3600000"
})
class QueueServiceTest {

    @Autowired
    private QueueService queueService;

    // unique per test so tests don't collide on shared Redis state
    private Long uniqueEventId() {
        return ThreadLocalRandom.current()
                                .nextLong(1_000_000_000L, 2_000_000_000L);
    }

    @Test
    void enqueueGrantsImmediateAccessUpToAdmitRateWhenBacklogEmpty() {
        Long eventId = uniqueEventId();

        String first = queueService.enqueue(eventId);
        String second = queueService.enqueue(eventId);

        assertThat(queueService.hasAccess(eventId, first)).isTrue();
        assertThat(queueService.hasAccess(eventId, second)).isTrue();
        assertThat(queueService.getPosition(eventId, first)).isNull();
        assertThat(queueService.getPosition(eventId, second)).isNull();
    }

    @Test
    void enqueueQueuesArrivalsBeyondAdmitRateWhenBacklogEmpty() {
        Long eventId = uniqueEventId();
        queueService.enqueue(eventId); // consumes escape-hatch slot 1
        queueService.enqueue(eventId); // consumes escape-hatch slot 2 (admit-rate=2)

        String overflow = queueService.enqueue(eventId);

        assertThat(queueService.hasAccess(eventId, overflow)).isFalse();
        assertThat(queueService.getPosition(eventId, overflow)).isEqualTo(0);
    }

    @Test
    void enqueueAssignsFifoPositionsInOrderAmongQueuedArrivals() {
        Long eventId = uniqueEventId();
        queueService.enqueue(eventId);
        queueService.enqueue(eventId); // both consume the admit-rate=2 escape hatch

        String first = queueService.enqueue(eventId);
        String second = queueService.enqueue(eventId);
        String third = queueService.enqueue(eventId);

        assertThat(queueService.getPosition(eventId, first)).isEqualTo(0);
        assertThat(queueService.getPosition(eventId, second)).isEqualTo(1);
        assertThat(queueService.getPosition(eventId, third)).isEqualTo(2);
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

        String token = queueService.enqueue(eventA); // backlog empty, under admit-rate -> fast-tracked

        assertThat(queueService.hasAccess(eventA, token)).isTrue();
        assertThat(queueService.hasAccess(eventB, token)).isFalse();
    }

    @Test
    void admitGrantsAccessUpToConfiguredRateFromBacklogInFifoOrder() {
        Long eventId = uniqueEventId();
        queueService.enqueue(eventId);
        queueService.enqueue(eventId); // consume the admit-rate=2 escape hatch

        String first = queueService.enqueue(eventId);
        String second = queueService.enqueue(eventId);
        String third = queueService.enqueue(eventId);

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
        queueService.enqueue(eventA);
        queueService.enqueue(eventA); // consume eventA's escape hatch
        queueService.enqueue(eventB);
        queueService.enqueue(eventB); // consume eventB's escape hatch

        String queuedA = queueService.enqueue(eventA);
        String queuedB = queueService.enqueue(eventB);

        queueService.admit();

        assertThat(queueService.hasAccess(eventA, queuedA)).isTrue();
        assertThat(queueService.hasAccess(eventB, queuedB)).isTrue();
    }

    @Test
    void checkStatusReflectsWaitingAdmittedAndInvalid() {
        Long eventId = uniqueEventId();
        String escapeHatchToken = queueService.enqueue(eventId);
        queueService.enqueue(eventId); // consume the admit-rate=2 escape hatch

        queueService.enqueue(eventId); // queued, will be popped by admit()
        queueService.enqueue(eventId); // queued, will be popped by admit()
        String waitingToken = queueService.enqueue(eventId); // queued, remains waiting

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
