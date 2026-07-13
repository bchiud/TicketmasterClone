package com.ticketmaster.queue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

// separate from QueueServiceTest because this one needs the real @Scheduled admit()
// loop running (short admit-interval-ms) to drain the backlog over time, rather than
// deterministic manual admit() calls.
@SpringBootTest
@TestPropertySource(properties = {
        "queue.admit-rate=2",
        "queue.admit-interval-ms=200",
        "booking.expiry-sweep-interval-ms=3600000"
})
class QueueServiceEscapeHatchTest {

    @Autowired
    private QueueService queueService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private Long uniqueEventId() {
        return ThreadLocalRandom.current()
                                .nextLong(7_000_000_000L, 8_000_000_000L);
    }

    @Test
    void escapeHatchReopensForNewArrivalsAfterBacklogFullyDrains() throws InterruptedException {
        Long eventId = uniqueEventId();

        // admit-rate=2: first 2 fast-tracked, next 4 forced into the backlog
        queueService.enqueue(eventId);
        queueService.enqueue(eventId);
        queueService.enqueue(eventId);
        queueService.enqueue(eventId);
        queueService.enqueue(eventId);
        queueService.enqueue(eventId);

        assertThat(stringRedisTemplate.opsForZSet().zCard("queue:" + eventId)).isEqualTo(4);

        // let the real @Scheduled admit() (every 200ms) fully drain the backlog,
        // and let the admittedCount window (also 200ms) lapse several times over
        Thread.sleep(1000);

        assertThat(stringRedisTemplate.opsForZSet().zCard("queue:" + eventId)).isEqualTo(0);
        assertThat(stringRedisTemplate.opsForSet().isMember("queue:active-events", eventId.toString()))
                .isFalse();

        String newArrivalToken = queueService.enqueue(eventId);

        assertThat(queueService.hasAccess(eventId, newArrivalToken)).isTrue();
        assertThat(queueService.getPosition(eventId, newArrivalToken)).isNull();
    }
}
