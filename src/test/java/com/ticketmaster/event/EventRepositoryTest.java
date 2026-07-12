package com.ticketmaster.event;

import com.ticketmaster.venue.Venue;
import com.ticketmaster.venue.VenueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EventRepositoryTest {

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

    @Test
    void savesAndFindsAnEvent() {
        Venue venue = saveVenue();

        Event event = new Event();
        event.setName("Taylor Swift: The Eras Tour");
        event.setPerformer("Taylor Swift");
        event.setVenue(venue);
        event.setStartsAt(ZonedDateTime.now()
                                       .plusMonths(1));

        Event saved = eventRepository.save(event);

        assertThat(saved.getId()).isNotNull();
        assertThat(eventRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void findsEventsByVenueId() {
        Venue venue = saveVenue();

        Event event = new Event();
        event.setName("Concert Night");
        event.setVenue(venue);
        event.setStartsAt(ZonedDateTime.now()
                                       .plusDays(10));
        eventRepository.save(event);

        List<Event> results = eventRepository.findByVenueId(venue.getId());

        assertThat(results).hasSize(1);
        assertThat(results.get(0)
                          .getName()).isEqualTo("Concert Night");
    }

    @Test
    void findsEventsByStatus() {
        Venue venue = saveVenue();

        Event event = new Event();
        event.setName("On Sale Show");
        event.setVenue(venue);
        event.setStartsAt(ZonedDateTime.now()
                                       .plusDays(5));
        event.setStatus(EventStatus.ON_SALE);
        eventRepository.save(event);

        List<Event> results = eventRepository.findByStatus(EventStatus.ON_SALE);

        assertThat(results).extracting(Event::getName)
                           .contains("On Sale Show");
    }

    @Test
    void findsEventsByNameContainingIgnoreCase() {
        Venue venue = saveVenue();

        Event event = new Event();
        event.setName("Summer Jam Festival");
        event.setVenue(venue);
        event.setStartsAt(ZonedDateTime.now()
                                       .plusDays(20));
        eventRepository.save(event);

        List<Event> results = eventRepository.findByNameContainingIgnoreCase("summer");

        assertThat(results).extracting(Event::getName)
                           .contains("Summer Jam Festival");
    }
}
