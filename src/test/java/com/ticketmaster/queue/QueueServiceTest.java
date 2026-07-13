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
    void enqueueAssignsFifoPositionsInOrder() {
        Long eventId = uniqueEventId();

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
        assertThat(queueService.hasAccess(null)).isFalse();
        assertThat(queueService.hasAccess("")).isFalse();
        assertThat(queueService.hasAccess("   ")).isFalse();
    }

    @Test
    void hasAccessReturnsFalseWhenNeverAdmitted() {
        assertThat(queueService.hasAccess("never-admitted-token")).isFalse();
    }

    @Test
    void admitGrantsAccessUpToConfiguredRateInFifoOrder() {
        Long eventId = uniqueEventId();
        String first = queueService.enqueue(eventId);
        String second = queueService.enqueue(eventId);
        String third = queueService.enqueue(eventId);

        queueService.admit(); // queue.admit-rate=2 for this test class

        assertThat(queueService.hasAccess(first)).isTrue();
        assertThat(queueService.hasAccess(second)).isTrue();
        assertThat(queueService.hasAccess(third)).isFalse();
        assertThat(queueService.getPosition(eventId, third)).isEqualTo(0);
    }

    @Test
    void admitAdmitsIndependentlyAcrossMultipleActiveEvents() {
        Long eventA = uniqueEventId();
        Long eventB = uniqueEventId();
        String tokenA = queueService.enqueue(eventA);
        String tokenB = queueService.enqueue(eventB);

        queueService.admit();

        assertThat(queueService.hasAccess(tokenA)).isTrue();
        assertThat(queueService.hasAccess(tokenB)).isTrue();
    }

    @Test
    void checkStatusReflectsWaitingAdmittedAndInvalid() {
        Long eventId = uniqueEventId();
        String admittedToken = queueService.enqueue(eventId);
        queueService.enqueue(eventId); // second slot, also consumes the rate=2 budget
        String waitingToken = queueService.enqueue(eventId);

        queueService.admit();

        assertThat(queueService.checkStatus(eventId, admittedToken).getQueueStatus())
                .isEqualTo(QueueStatus.ADMITTED);
        assertThat(queueService.checkStatus(eventId, waitingToken).getQueueStatus())
                .isEqualTo(QueueStatus.WAITING);
        assertThat(queueService.checkStatus(eventId, waitingToken).getPosition())
                .isEqualTo(0);
        assertThat(queueService.checkStatus(eventId, "bogus-token").getQueueStatus())
                .isEqualTo(QueueStatus.INVALID);
    }
}
