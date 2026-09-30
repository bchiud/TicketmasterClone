package com.ticketmaster.booking;

import com.ticketmaster.event.Event;
import com.ticketmaster.event.EventRepository;
import com.ticketmaster.event.EventStatus;
import com.ticketmaster.payment.PaymentRepository;
import com.ticketmaster.payment.PaymentService;
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
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

// Regression guard: the expiry sweep must not release the seats of a booking that was paid mid-sweep.
//
// expire() lists PENDING bookings, then reloads each one in its own transaction. If a payment commits
// between the list and the reload, the reload sees the CONFIRMED booking at its new @Version, so
// optimistic locking can't catch the conflict. Without a status re-check after the reload, the sweep
// marks the paid booking EXPIRED and puts its BOOKED tickets back on sale: a double-booking.
//
// The race is forced deterministically: a spy on the sweep query runs the real query, then commits a
// real payment before handing the now-stale list back to expire(). Non-transactional @SpringBootTest
// for the same reason as BookingExpirySweepTest: every step must really commit.
//
// @DirtiesContext: the spy bean gives this class its own Spring context, and every cached context keeps
// its own connection pool open. Closing this one afterwards keeps the suite under Postgres's
// max_connections; without it, later @DataJpaTest classes fail to get a connection.
@DirtiesContext
@SpringBootTest
@TestPropertySource(properties = {
        // silence the background schedulers so the only mutations are the ones this test drives
        "booking.expiry-sweep-interval-ms=3600000",
        "event.on-sale-sweep-interval-ms=3600000",
        "queue.admit-interval-ms=3600000"
})
class BookingExpiryPaymentRaceTest {

    @MockitoSpyBean
    private BookingRepository bookingRepository;
    @Autowired
    private BookingService bookingService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private SeatRepository seatRepository;
    @Autowired
    private VenueRepository venueRepository;
    @Autowired
    private UserRepository userRepository;

    private Long bookingId, ticketId, eventId, seatId, venueId, userId;
    private List<Booking> sweptBookings;

    @Test
    void expireLeavesABookingPaidAfterTheSweepQueryConfirmed() {
        User user = new User();
        user.setEmail("expire-race-" + UUID.randomUUID() + "@example.com");
        userId = userRepository.save(user).getId();

        Venue venue = new Venue();
        venue.setName("Race Venue");
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
        booking.setIdempotencyKey("expire-race-" + UUID.randomUUID());
        booking.setExpiresAt(Instant.now().minusSeconds(60)); // past its hold window, so the sweep picks it up
        bookingId = bookingRepository.save(booking).getId();

        // Ticket owns the association (mappedBy = "booking"); link the owning side, as hold() does
        ticket.setBooking(booking);
        ticketRepository.save(ticket);

        // Spring spies a JDK-proxy repository by delegating to it, so callRealMethod() has no concrete
        // method to call; the spy's default answer is that delegation, i.e. the real query.
        Answer<?> realQuery = mockingDetails(bookingRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<Booking> swept = (List<Booking>) realQuery.answer(invocation);
            sweptBookings = swept;

            // In production the payer passed confirm()'s expiry check just before the deadline, and the
            // payment commits after the sweep query. Model the first part by moving the deadline forward,
            // then commit the payment while the sweep still holds its stale PENDING snapshot.
            Booking current = bookingRepository.findById(bookingId).orElseThrow();
            current.setExpiresAt(Instant.now().plusSeconds(60));
            bookingRepository.save(current);
            paymentService.pay(bookingId);

            return swept;
        }).when(bookingRepository).findByStatusAndExpiresAtBefore(eq(BookingStatus.PENDING), any(Instant.class));

        bookingService.expire();

        // the race really happened: the sweep saw this booking as PENDING
        assertThat(sweptBookings).extracting(Booking::getId).contains(bookingId);

        assertThat(bookingRepository.findById(bookingId)).get()
                                                         .extracting(Booking::getStatus)
                                                         .isEqualTo(BookingStatus.CONFIRMED);
        Ticket after = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TicketStatus.BOOKED);
        assertThat(after.getBooking()).isNotNull();
        assertThat(after.getBooking().getId()).isEqualTo(bookingId);
    }

    @AfterEach
    void cleanup() {
        // children first: payments and ticket -> booking -> event/seat -> venue -> user
        if (bookingId != null) paymentRepository.deleteAll(paymentRepository.findByBookingId(bookingId));
        if (ticketId != null) ticketRepository.deleteById(ticketId);
        if (bookingId != null) bookingRepository.deleteById(bookingId);
        if (eventId != null) eventRepository.deleteById(eventId);
        if (seatId != null) seatRepository.deleteById(seatId);
        if (venueId != null) venueRepository.deleteById(venueId);
        if (userId != null) userRepository.deleteById(userId);
    }
}
