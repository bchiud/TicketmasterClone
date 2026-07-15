package com.ticketmaster.queue;

import com.ticketmaster.event.Event;
import com.ticketmaster.event.EventRepository;
import com.ticketmaster.event.EventStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// unlike QueueServiceTest (which holds the limit high to test queue mechanics), this suite pins
// the limit low to exercise the rate limiter itself. window is effectively infinite so the count
// never resets mid-test, and every test uses a fresh event so the per-(event, ip) key is isolated.
@SpringBootTest
@TestPropertySource(properties = {
        "queue.admit-rate=2",
        "queue.admit-interval-ms=3600000",
        "queue.enqueue-limit=3",
        "queue.enqueue-window-ms=3600000",
        "booking.expiry-sweep-interval-ms=3600000"
})
class QueueServiceRateLimitTest {

    // must match queue.enqueue-limit above
    private static final int LIMIT = 3;

    @Autowired
    private QueueService queueService;

    @Autowired
    private EventRepository eventRepository;

    private Long uniqueEventId() {
        Event event = new Event();
        event.setStatus(EventStatus.ON_SALE);
        return eventRepository.save(event)
                              .getId();
    }

    @Test
    void enqueueAllowsUpToTheLimitThenRejects() {
        Long eventId = uniqueEventId();
        String ip = "1.1.1.1";

        for (int i = 0; i < LIMIT; i++) {
            assertThat(queueService.enqueue(eventId, ip)).isNotNull();
        }

        assertThatThrownBy(() -> queueService.enqueue(eventId, ip))
                .isInstanceOf(RateLimitException.class);
    }

    @Test
    void rateLimitIsScopedPerClientIp() {
        Long eventId = uniqueEventId();
        String exhausted = "2.2.2.2";
        String fresh = "3.3.3.3";

        for (int i = 0; i < LIMIT; i++) queueService.enqueue(eventId, exhausted);
        assertThatThrownBy(() -> queueService.enqueue(eventId, exhausted))
                .isInstanceOf(RateLimitException.class);

        // a different ip against the same event has its own counter and is unaffected
        assertThatCode(() -> queueService.enqueue(eventId, fresh)).doesNotThrowAnyException();
    }

    @Test
    void rateLimitIsScopedPerEvent() {
        Long exhaustedEvent = uniqueEventId();
        Long freshEvent = uniqueEventId();
        String ip = "4.4.4.4";

        for (int i = 0; i < LIMIT; i++) queueService.enqueue(exhaustedEvent, ip);
        assertThatThrownBy(() -> queueService.enqueue(exhaustedEvent, ip))
                .isInstanceOf(RateLimitException.class);

        // same ip against a different event has its own counter and is unaffected
        assertThatCode(() -> queueService.enqueue(freshEvent, ip)).doesNotThrowAnyException();
    }
}
