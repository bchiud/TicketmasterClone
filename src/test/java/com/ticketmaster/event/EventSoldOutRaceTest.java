package com.ticketmaster.event;

import com.ticketmaster.booking.Booking;
import com.ticketmaster.booking.BookingRepository;
import com.ticketmaster.booking.BookingStatus;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

// Regression guard: when the last two seats are paid for at the same moment, the event must end up SOLD_OUT.
//
// markSoldOutIfLastTicketBooked() runs inside each confirm's transaction and counts the event's AVAILABLE/HELD
// tickets. Under READ COMMITTED, each confirm sees the OTHER one's ticket still HELD (not committed yet), so both
// count 1 and neither flips the event: write skew. The load test saw events stuck ON_SALE this way (3 of 12).
// The fix locks the event row before counting, so the second confirm waits for the first to commit and then
// counts 0.
//
// Forced deterministically: a spy on the count makes each confirm wait (up to 2 s) at a barrier right after
// counting, so without the lock both counts happen before either commit. With the lock, the second confirm
// can't reach its count while the first holds the lock, so the first times out at the barrier and commits; the
// second then counts 0 (and finds the barrier already broken, so it doesn't wait).
//
// Non-transactional @SpringBootTest so each payment really commits on its own thread. @DirtiesContext: the spy
// bean gives this class its own Spring context; closing it afterwards keeps the suite under Postgres's
// max_connections.
@DirtiesContext
@SpringBootTest
@TestPropertySource(properties = {
        // silence the background schedulers so the only mutations are the ones this test drives
        "booking.expiry-sweep-interval-ms=3600000",
        "event.on-sale-sweep-interval-ms=3600000",
        "queue.admit-interval-ms=3600000"
})
class EventSoldOutRaceTest {

    @MockitoSpyBean
    private TicketRepository ticketRepository;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private SeatRepository seatRepository;
    @Autowired
    private VenueRepository venueRepository;
    @Autowired
    private UserRepository userRepository;

    private final List<Long> ticketIds = new ArrayList<>();
    private final List<Long> bookingIds = new ArrayList<>();
    private final List<Long> seatIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private Long eventId, venueId;

    @Test
    void concurrentPaymentsForTheLastTwoSeatsMarkTheEventSoldOut() throws InterruptedException {
        Venue venue = new Venue();
        venue.setName("Sold-out Race Venue");
        venueId = venueRepository.save(venue).getId();

        Event event = new Event();
        event.setVenue(venue);
        event.setStatus(EventStatus.ON_SALE);
        eventId = eventRepository.save(event).getId();

        // two seats, each held by a different user's PENDING booking: the event's entire inventory
        for (int i = 0; i < 2; i++) {
            User user = new User();
            user.setEmail("sold-out-race-" + UUID.randomUUID() + "@example.com");
            userIds.add(userRepository.save(user).getId());

            Seat seat = new Seat();
            seat.setVenue(venue);
            seat.setSection("A");
            seat.setRowLabel("1");
            seat.setSeatNumber(UUID.randomUUID().toString());
            seatIds.add(seatRepository.save(seat).getId());

            Ticket ticket = new Ticket();
            ticket.setEvent(event);
            ticket.setSeat(seat);
            ticket.setPriceCents(100);
            ticket.setStatus(TicketStatus.HELD);
            ticketIds.add(ticketRepository.save(ticket).getId());

            Booking booking = new Booking();
            booking.setUser(user);
            booking.setEvent(event);
            booking.setTickets(List.of(ticket));
            booking.setStatus(BookingStatus.PENDING);
            booking.setTotalCents(100);
            booking.setIdempotencyKey("sold-out-race-" + UUID.randomUUID());
            booking.setExpiresAt(Instant.now().plusSeconds(600));
            bookingIds.add(bookingRepository.save(booking).getId());

            // Ticket owns the association (mappedBy = "booking"); link the owning side, as hold() does
            ticket.setBooking(booking);
            ticketRepository.save(ticket);
        }

        // Spring spies a JDK-proxy repository by delegating to it, so callRealMethod() has no concrete method to
        // call; the spy's default answer is that delegation, i.e. the real count query.
        Answer<?> realCount = mockingDetails(ticketRepository).getMockCreationSettings().getDefaultAnswer();
        CyclicBarrier bothCounted = new CyclicBarrier(2);
        doAnswer(invocation -> {
            Object count = realCount.answer(invocation);
            try {
                bothCounted.await(2, TimeUnit.SECONDS);
            } catch (TimeoutException | BrokenBarrierException e) {
                // expected with the event lock: the other confirm can't count until this one commits
            }
            return count;
        }).when(ticketRepository).countByEventIdAndStatusIn(any(), anyList());

        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> payers = new ArrayList<>();
        for (Long bookingId : bookingIds) {
            Thread payer = new Thread(() -> {
                try {
                    paymentService.pay(bookingId);
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
            payers.add(payer);
            payer.start();
        }
        for (Thread payer : payers) payer.join(10_000);

        assertThat(failures).as("both payments succeed").isEmpty();
        for (Long bookingId : bookingIds) {
            assertThat(bookingRepository.findById(bookingId)).get()
                                                             .extracting(Booking::getStatus)
                                                             .isEqualTo(BookingStatus.CONFIRMED);
        }
        assertThat(eventRepository.findById(eventId)).get()
                                                     .extracting(Event::getStatus)
                                                     .isEqualTo(EventStatus.SOLD_OUT);
    }

    // The sold-out check must read the event's CURRENT status, not the copy this transaction loaded earlier.
    // confirm() reads the event (its "Event cancelled" check) before markSoldOutIfLastTicketBooked() runs; if an
    // admin cancels the event in between, that in-memory copy still says ON_SALE. A locking query alone doesn't
    // fix it: Hibernate returns the already-loaded instance without re-reading it. refresh(..., PESSIMISTIC_WRITE)
    // locks AND re-reads, so the cancelled event is left CANCELLED instead of being overwritten with SOLD_OUT.
    @Test
    void lastSeatConfirmDoesNotOverwriteACancellationCommittedMidConfirm() throws InterruptedException {
        Venue venue = new Venue();
        venue.setName("Cancel Race Venue");
        venueId = venueRepository.save(venue).getId();

        Event event = new Event();
        event.setVenue(venue);
        event.setStatus(EventStatus.ON_SALE);
        eventId = eventRepository.save(event).getId();

        User user = new User();
        user.setEmail("cancel-race-" + UUID.randomUUID() + "@example.com");
        userIds.add(userRepository.save(user).getId());

        Seat seat = new Seat();
        seat.setVenue(venue);
        seat.setSection("A");
        seat.setRowLabel("1");
        seat.setSeatNumber(UUID.randomUUID().toString());
        seatIds.add(seatRepository.save(seat).getId());

        // the event's only ticket, so confirming it would make the event sell out
        Ticket ticket = new Ticket();
        ticket.setEvent(event);
        ticket.setSeat(seat);
        ticket.setPriceCents(100);
        ticket.setStatus(TicketStatus.HELD);
        ticketIds.add(ticketRepository.save(ticket).getId());

        Booking booking = new Booking();
        booking.setUser(user);
        booking.setEvent(event);
        booking.setTickets(List.of(ticket));
        booking.setStatus(BookingStatus.PENDING);
        booking.setTotalCents(100);
        booking.setIdempotencyKey("cancel-race-" + UUID.randomUUID());
        booking.setExpiresAt(Instant.now().plusSeconds(600));
        Long bookingId = bookingRepository.save(booking).getId();
        bookingIds.add(bookingId);

        ticket.setBooking(booking);
        ticketRepository.save(ticket);

        // confirm() has passed its "Event cancelled" check by the time it saves the tickets as BOOKED; cancel the
        // event right then, in another transaction, before the sold-out check runs
        Answer<?> realSaveAll = mockingDetails(ticketRepository).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object saved = realSaveAll.answer(invocation);
            Thread admin = new Thread(() -> {
                Event current = eventRepository.findById(eventId).orElseThrow();
                current.setStatus(EventStatus.CANCELLED);
                eventRepository.save(current);
            });
            admin.start();
            admin.join();
            return saved;
        }).when(ticketRepository).saveAll(any());

        paymentService.pay(bookingId);

        assertThat(eventRepository.findById(eventId)).get()
                                                     .extracting(Event::getStatus)
                                                     .isEqualTo(EventStatus.CANCELLED);
    }

    @AfterEach
    void cleanup() {
        // children first: payments and tickets -> bookings -> event/seats -> venue -> users
        for (Long bookingId : bookingIds) paymentRepository.deleteAll(paymentRepository.findByBookingId(bookingId));
        ticketRepository.deleteAllById(ticketIds);
        bookingRepository.deleteAllById(bookingIds);
        if (eventId != null) eventRepository.deleteById(eventId);
        seatRepository.deleteAllById(seatIds);
        if (venueId != null) venueRepository.deleteById(venueId);
        userRepository.deleteAllById(userIds);
    }
}
