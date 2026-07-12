package com.ticketmaster.ticket;

import com.ticketmaster.event.Event;
import com.ticketmaster.event.EventRepository;
import com.ticketmaster.seat.Seat;
import com.ticketmaster.seat.SeatRepository;
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
class TicketRepositoryTest {

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private VenueRepository venueRepository;

    private Venue saveVenue() {
        Venue venue = new Venue();
        venue.setName("Madison Square Garden");
        venue.setCity("New York");
        venue.setAddress("4 Pennsylvania Plaza");
        return venueRepository.save(venue);
    }

    private Event saveEvent(Venue venue) {
        Event event = new Event();
        event.setName("Test Concert");
        event.setVenue(venue);
        event.setStartsAt(ZonedDateTime.now().plusDays(30));
        return eventRepository.save(event);
    }

    private Seat saveSeat(Venue venue) {
        Seat seat = new Seat();
        seat.setVenue(venue);
        seat.setSection("A");
        seat.setRowLabel("1");
        seat.setSeatNumber("1");
        return seatRepository.save(seat);
    }

    @Test
    void savesAndFindsATicket() {
        Venue venue = saveVenue();
        Event event = saveEvent(venue);
        Seat seat = saveSeat(venue);

        Ticket ticket = new Ticket();
        ticket.setEvent(event);
        ticket.setSeat(seat);
        ticket.setPriceCents(5000);

        Ticket saved = ticketRepository.save(ticket);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(TicketStatus.AVAILABLE);
    }

    @Test
    void findsTicketsByEventId() {
        Venue venue = saveVenue();
        Event event = saveEvent(venue);
        Seat seat = saveSeat(venue);

        Ticket ticket = new Ticket();
        ticket.setEvent(event);
        ticket.setSeat(seat);
        ticket.setPriceCents(5000);
        ticketRepository.save(ticket);

        List<Ticket> results = ticketRepository.findByEventId(event.getId());

        assertThat(results).hasSize(1);
    }

    @Test
    void findsTicketsByEventIdAndStatus() {
        Venue venue = saveVenue();
        Event event = saveEvent(venue);
        Seat seat = saveSeat(venue);

        Ticket ticket = new Ticket();
        ticket.setEvent(event);
        ticket.setSeat(seat);
        ticket.setPriceCents(5000);
        ticket.setStatus(TicketStatus.HELD);
        ticketRepository.save(ticket);

        List<Ticket> heldTickets = ticketRepository.findByEventIdAndStatus(event.getId(), TicketStatus.HELD);
        List<Ticket> bookedTickets = ticketRepository.findByEventIdAndStatus(event.getId(), TicketStatus.BOOKED);

        assertThat(heldTickets).hasSize(1);
        assertThat(bookedTickets).isEmpty();
    }
}
