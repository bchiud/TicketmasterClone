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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

// Regression guard: a payment that commits AFTER the expiry sweep reloads a booking, but BEFORE the sweep
// commits, must be handled as a skip, not an error.
//
// This is the other half of BookingExpiryPaymentRaceTest. There, the payment commits before the reload, and
// the status re-check skips the booking. Here, the sweep reloads the booking at version N while it's still
// PENDING, the payment then commits version N+1, and the sweep's UPDATE ... WHERE version = N matches no row.
// @Version keeps the data correct on its own; what this test pins is expire()'s
// catch (ConcurrencyFailureException), which logs the conflict as an INFO skip. Without that catch the
// conflict falls into catch (Exception) and is logged as an ERROR with a stack trace, while every data
// assertion still passes. So the log assertion is the one that catches the regression.
//
// Forced deterministically: a spy on findById lets the sweep's reload run, then commits a payment on another
// thread (its own transaction; the same thread would join the sweep's) before handing the reloaded booking
// back to the sweep.
//
// @DirtiesContext: the spy bean gives this class its own Spring context; closing it afterwards keeps the
// suite under Postgres's max_connections (see BookingExpiryPaymentRaceTest).
@DirtiesContext
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        // silence the background schedulers so the only mutations are the ones this test drives
        "booking.expiry-sweep-interval-ms=3600000",
        "event.on-sale-sweep-interval-ms=3600000",
        "queue.admit-interval-ms=3600000"
})
class BookingExpiryVersionConflictTest {

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

    @Test
    void expireLogsAVersionConflictWithAConcurrentPaymentAsASkip(CapturedOutput output) {
        User user = new User();
        user.setEmail("expire-conflict-" + UUID.randomUUID() + "@example.com");
        userId = userRepository.save(user).getId();

        Venue venue = new Venue();
        venue.setName("Conflict Venue");
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
        booking.setIdempotencyKey("expire-conflict-" + UUID.randomUUID());
        booking.setExpiresAt(Instant.now().minusSeconds(60)); // past its hold window, so the sweep picks it up
        bookingId = bookingRepository.save(booking).getId();

        // Ticket owns the association (mappedBy = "booking"); link the owning side, as hold() does
        ticket.setBooking(booking);
        ticketRepository.save(ticket);

        // Spring spies a JDK-proxy repository by delegating to it, so callRealMethod() has no concrete
        // method to call; the spy's default answer is that delegation, i.e. the real findById.
        Answer<?> realFindById = mockingDetails(bookingRepository).getMockCreationSettings().getDefaultAnswer();
        AtomicBoolean paid = new AtomicBoolean();
        AtomicReference<Throwable> payerFailure = new AtomicReference<>();
        doAnswer(invocation -> {
            Object reloaded = realFindById.answer(invocation);
            // one-shot: only the sweep's reload triggers the payment; the payer's own findById calls pass through
            if (bookingId.equals(invocation.getArgument(0)) && paid.compareAndSet(false, true)) {
                Thread payer = new Thread(() -> {
                    try {
                        // the payer passed confirm()'s expiry check before the deadline; model it by moving
                        // the deadline forward. This commits a version bump too, which is fine: the sweep's
                        // reload above already holds the older version.
                        Booking current = bookingRepository.findById(bookingId).orElseThrow();
                        current.setExpiresAt(Instant.now().plusSeconds(60));
                        bookingRepository.save(current);
                        paymentService.pay(bookingId);
                    } catch (Throwable t) {
                        payerFailure.set(t);
                    }
                });
                payer.start();
                payer.join();
            }
            return reloaded;
        }).when(bookingRepository).findById(any());

        bookingService.expire();

        assertThat(paid).as("the sweep reloaded the booking, triggering the concurrent payment").isTrue();
        assertThat(payerFailure.get()).as("concurrent payment").isNull();

        // @Version rejected the sweep's update: the paid booking and its seat are untouched
        assertThat(bookingRepository.findById(bookingId)).get()
                                                         .extracting(Booking::getStatus)
                                                         .isEqualTo(BookingStatus.CONFIRMED);
        Ticket after = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TicketStatus.BOOKED);
        assertThat(after.getBooking()).isNotNull();
        assertThat(after.getBooking().getId()).isEqualTo(bookingId);

        // ...and the sweep treated the conflict as an expected skip, not a failure
        assertThat(output.getAll())
                .contains("Skipped expiring booking " + bookingId + ": modified concurrently")
                .doesNotContain("Failed to expire booking " + bookingId);
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
