package com.ticketmaster.booking;

import com.ticketmaster.event.Event;
import com.ticketmaster.event.EventRepository;
import com.ticketmaster.event.EventStatus;
import com.ticketmaster.seat.Seat;
import com.ticketmaster.seat.SeatRepository;
import com.ticketmaster.ticket.Ticket;
import com.ticketmaster.ticket.TicketRepository;
import com.ticketmaster.ticket.TicketStatus;
import com.ticketmaster.user.User;
import com.ticketmaster.user.UserRepository;
import com.ticketmaster.venue.Venue;
import com.ticketmaster.venue.VenueRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Regression guard for the expiry-sweep LazyInitializationException.
//
// This is deliberately a non-transactional @SpringBootTest, NOT @DataJpaTest. The bug only
// exists when expire() re-queries bookings in a fresh transaction and their lazy `tickets`
// collection is accessed while detached (the scheduler-thread path). @DataJpaTest wraps the
// whole test in one open session, which keeps the entity attached and hides the bug - which is
// exactly how the original defect slipped through. Here the setup is committed, so expire()
// genuinely sees detached bookings. @AfterEach deletes only the rows this test created (JUnit
// runs it even after a failing assertion), so the shared test DB is left clean.
@SpringBootTest
@TestPropertySource(properties = {
        // silence the background schedulers so the only mutation is our manual expire() call
        "booking.expiry-sweep-interval-ms=3600000",
        "event.on-sale-sweep-interval-ms=3600000",
        "queue.admit-interval-ms=3600000"
})
class BookingExpirySweepTest {

    @Autowired private BookingService bookingService;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private VenueRepository venueRepository;
    @Autowired private UserRepository userRepository;

    private Long bookingId, ticketId, eventId, seatId, venueId, userId;

    @Test
    void expireReleasesAStalePendingBookingAndItsTickets() {
        User user = new User();
        user.setEmail("expire-sweep-" + UUID.randomUUID() + "@example.com");
        userId = userRepository.save(user).getId();

        Venue venue = new Venue();
        venue.setName("Sweep Venue");
        venueId = venueRepository.save(venue).getId();

        Seat seat = new Seat();
        seat.setVenue(venue);
        seat.setSection("A");
        seat.setRowLabel("1");
        seat.setSeatNumber(UUID.randomUUID().toString());
        seatId = seatRepository.save(seat).getId();

        Event event = new Event();
        event.setVenue(venue);
        event.setStatus(EventStatus.ON_SALE);
        eventId = eventRepository.save(event).getId();

        Ticket ticket = new Ticket();
        ticket.setEvent(event);
        ticket.setSeat(seat);
        ticket.setPriceCents(100);
        ticket.setStatus(TicketStatus.HELD);
        ticketId = ticketRepository.save(ticket).getId();

        Booking booking = new Booking();
        booking.setUser(user);
        booking.setEvent(event);
        booking.setTickets(List.of(ticket));
        booking.setStatus(BookingStatus.PENDING);
        booking.setTotalCents(100);
        booking.setIdempotencyKey("expire-sweep-" + UUID.randomUUID());
        booking.setExpiresAt(Instant.now().minusSeconds(60)); // already past its hold window
        bookingId = bookingRepository.save(booking).getId();

        // Ticket owns the association (mappedBy = "booking"), so link the owning side and persist,
        // mirroring how BookingService.hold() populates it. Setting only booking.setTickets(...)
        // (the inverse side) would not write the FK, and the sweep would find no tickets to release.
        ticket.setBooking(booking);
        ticketRepository.save(ticket);

        bookingService.expire();

        assertThat(bookingRepository.findById(bookingId)).get()
                .extracting(Booking::getStatus).isEqualTo(BookingStatus.EXPIRED);
        assertThat(ticketRepository.findById(ticketId)).get()
                .extracting(Ticket::getStatus).isEqualTo(TicketStatus.AVAILABLE);
        // released tickets detach from the expired booking
        assertThat(ticketRepository.findById(ticketId)).get()
                .extracting(Ticket::getBooking).isNull();
    }

    @AfterEach
    void cleanup() {
        // FK order: tickets.booking_id now references bookings, so the ticket must go before the
        // booking. (Children first: ticket -> booking -> event/seat -> venue -> user.)
        if (ticketId != null) ticketRepository.deleteById(ticketId);
        if (bookingId != null) bookingRepository.deleteById(bookingId);
        if (eventId != null) eventRepository.deleteById(eventId);
        if (seatId != null) seatRepository.deleteById(seatId);
        if (venueId != null) venueRepository.deleteById(venueId);
        if (userId != null) userRepository.deleteById(userId);
    }
}
