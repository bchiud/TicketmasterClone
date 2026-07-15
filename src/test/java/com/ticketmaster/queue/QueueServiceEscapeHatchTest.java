package com.ticketmaster.queue;

import com.ticketmaster.event.Event;
import com.ticketmaster.event.EventRepository;
import com.ticketmaster.event.EventStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

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

    @Autowired
    private EventRepository eventRepository;

    // enqueue() gates on event status, so we need a real ON_SALE event to queue against
    private Long uniqueEventId() {
        Event event = new Event();
        event.setStatus(EventStatus.ON_SALE);
        return eventRepository.save(event)
                              .getId();
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
