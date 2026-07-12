package com.ticketmaster.booking;

import com.ticketmaster.event.Event;
import com.ticketmaster.event.EventRepository;
import com.ticketmaster.seat.Seat;
import com.ticketmaster.seat.SeatRepository;
import com.ticketmaster.ticket.Ticket;
import com.ticketmaster.ticket.TicketRepository;
import com.ticketmaster.user.User;
import com.ticketmaster.user.UserRepository;
import com.ticketmaster.venue.Venue;
import com.ticketmaster.venue.VenueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BookingRepositoryTest {

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private TicketRepository ticketRepository;

    private User saveUser() {
        User user = new User();
        user.setEmail("alice@example.com");
        user.setName("Alice");
        return userRepository.save(user);
    }

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
        event.setStartsAt(ZonedDateTime.now()
                                       .plusDays(30));
        return eventRepository.save(event);
    }

    private Ticket saveTicket(Event event, Venue venue, String seatNumber) {
        Seat seat = new Seat();
        seat.setVenue(venue);
        seat.setSection("A");
        seat.setRowLabel("1");
        seat.setSeatNumber(seatNumber);
        seatRepository.save(seat);

        Ticket ticket = new Ticket();
        ticket.setEvent(event);
        ticket.setSeat(seat);
        ticket.setPriceCents(5000);
        return ticketRepository.save(ticket);
    }

    private Booking newBooking(User user, Event event, String idempotencyKey) {
        Booking booking = new Booking();
        booking.setUser(user);
        booking.setEvent(event);
        booking.setTotalCents(5000);
        booking.setIdempotencyKey(idempotencyKey);
        booking.setExpiresAt(Instant.now()
                                     .plusSeconds(600));
        return booking;
    }

    @Test
    void savesAndFindsABooking() {
        User user = saveUser();
        Venue venue = saveVenue();
        Event event = saveEvent(venue);

        Booking saved = bookingRepository.save(newBooking(user, event, "idem-1"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void findsBookingByIdempotencyKey() {
        User user = saveUser();
        Venue venue = saveVenue();
        Event event = saveEvent(venue);
        bookingRepository.save(newBooking(user, event, "idem-2"));

        Optional<Booking> found = bookingRepository.findByIdempotencyKey("idem-2");

        assertThat(found).isPresent();
        assertThat(found.get()
                        .getUser()
                        .getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void findsBookingsByUserId() {
        User user = saveUser();
        Venue venue = saveVenue();
        Event event = saveEvent(venue);
        bookingRepository.save(newBooking(user, event, "idem-3"));

        List<Booking> results = bookingRepository.findByUserId(user.getId());

        assertThat(results).hasSize(1);
    }

    @Test
    void findsBookingsByStatus() {
        User user = saveUser();
        Venue venue = saveVenue();
        Event event = saveEvent(venue);
        Booking booking = newBooking(user, event, "idem-4");
        booking.setStatus(BookingStatus.CONFIRMED);
        bookingRepository.save(booking);

        List<Booking> confirmed = bookingRepository.findByStatus(BookingStatus.CONFIRMED);
        List<Booking> pending = bookingRepository.findByStatus(BookingStatus.PENDING);

        assertThat(confirmed).hasSize(1);
        assertThat(pending).isEmpty();
    }

    @Test
    void findsExpiredPendingBookingsBeforeCutoff() {
        User user = saveUser();
        Venue venue = saveVenue();
        Event event = saveEvent(venue);
        Booking booking = newBooking(user, event, "idem-5");
        booking.setExpiresAt(Instant.now()
                                     .minusSeconds(60));
        bookingRepository.save(booking);

        List<Booking> expired = bookingRepository.findByStatusAndExpiresAtBefore(
                BookingStatus.PENDING, Instant.now());
        List<Booking> notYetExpired = bookingRepository.findByStatusAndExpiresAtBefore(
                BookingStatus.PENDING, Instant.now()
                                               .minusSeconds(120));

        assertThat(expired).hasSize(1);
        assertThat(notYetExpired).isEmpty();
    }

    @Test
    void bookingCanHaveMultipleTickets() {
        User user = saveUser();
        Venue venue = saveVenue();
        Event event = saveEvent(venue);
        Ticket ticketOne = saveTicket(event, venue, "1");
        Ticket ticketTwo = saveTicket(event, venue, "2");

        Booking booking = newBooking(user, event, "idem-6");
        booking.setTickets(List.of(ticketOne, ticketTwo));
        Booking saved = bookingRepository.save(booking);

        assertThat(bookingRepository.findById(saved.getId()))
                .isPresent()
                .get()
                .extracting(b -> b.getTickets()
                                   .size())
                .isEqualTo(2);
    }
}
