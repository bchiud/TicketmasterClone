package com.ticketmaster.event;

import com.ticketmaster.ticket.TicketRepository;
import com.ticketmaster.ticket.TicketService;
import com.ticketmaster.venue.VenueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// activateOnSaleEvents() catches per-event failures so one bad event doesn't block the rest from
// going on sale. A plain Mockito unit test forces the first save to fail; the DB-backed happy
// paths live in EventServiceTest.
@ExtendWith(MockitoExtension.class)
class EventOnSaleSweepFailureIsolationTest {

    @Mock
    private EventRepository eventRepository;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private TicketService ticketService;
    @Mock
    private VenueRepository venueRepository;

    @InjectMocks
    private EventService eventService;

    @Test
    void activateOnSaleEventsContinuesPastAnEventThatFailsToSave() {
        Event broken = new Event();
        broken.setId(1L);
        broken.setStatus(EventStatus.SCHEDULED);
        Event healthy = new Event();
        healthy.setId(2L);
        healthy.setStatus(EventStatus.SCHEDULED);

        when(eventRepository.findByStatusAndOnSaleAtBefore(eq(EventStatus.SCHEDULED), any(ZonedDateTime.class)))
                .thenReturn(List.of(broken, healthy));
        when(eventRepository.save(broken)).thenThrow(new RuntimeException("db down"));

        eventService.activateOnSaleEvents();

        verify(eventRepository).save(healthy);
        assertThat(healthy.getStatus()).isEqualTo(EventStatus.ON_SALE);
    }
}
