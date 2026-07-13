package com.ticketmaster.event;

import com.ticketmaster.venue.Venue;
import com.ticketmaster.venue.VenueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(EventService.class)
class EventServiceTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private VenueRepository venueRepository;

    private Venue saveVenue() {
        Venue venue = new Venue();
        venue.setName("Madison Square Garden");
        venue.setCity("New York");
        venue.setAddress("4 Pennsylvania Plaza");
        return venueRepository.save(venue);
    }

    private Event saveEvent(ZonedDateTime onSaleAt) {
        Venue venue = saveVenue();
        Event event = new Event();
        event.setName("Test Concert");
        event.setVenue(venue);
        event.setStartsAt(ZonedDateTime.now()
                                       .plusDays(30));
        event.setOnSaleAt(onSaleAt);
        return eventRepository.save(event);
    }

    @Test
    void activatesAScheduledEventWhosOnSaleTimeHasPassed() {
        Event event = saveEvent(ZonedDateTime.now()
                                              .minusMinutes(1));

        eventService.activateOnSaleEvents();

        assertThat(eventRepository.findById(event.getId()))
                .isPresent()
                .get()
                .extracting(Event::getStatus)
                .isEqualTo(EventStatus.ON_SALE);
    }

    @Test
    void doesNotActivateAnEventWhosOnSaleTimeHasNotYetArrived() {
        Event event = saveEvent(ZonedDateTime.now()
                                              .plusDays(1));

        eventService.activateOnSaleEvents();

        assertThat(eventRepository.findById(event.getId()))
                .isPresent()
                .get()
                .extracting(Event::getStatus)
                .isEqualTo(EventStatus.SCHEDULED);
    }

    @Test
    void doesNotReactivateAnEventThatIsAlreadyPastScheduled() {
        Event event = saveEvent(ZonedDateTime.now()
                                              .minusMinutes(1));
        event.setStatus(EventStatus.SOLD_OUT);
        eventRepository.save(event);

        eventService.activateOnSaleEvents();

        assertThat(eventRepository.findById(event.getId()))
                .isPresent()
                .get()
                .extracting(Event::getStatus)
                .isEqualTo(EventStatus.SOLD_OUT);
    }
}
