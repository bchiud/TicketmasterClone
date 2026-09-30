package com.ticketmaster.booking;

import com.ticketmaster.event.EventService;
import com.ticketmaster.queue.QueueService;
import com.ticketmaster.ticket.Ticket;
import com.ticketmaster.ticket.TicketRepository;
import com.ticketmaster.ticket.TicketStatus;
import com.ticketmaster.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

// expire() runs each booking in its own transaction and catches per-booking failures, so one bad
// booking must not stop the sweep. That catch branch is hard to trigger against a real DB (it
// needs a row to vanish between the sweep query and the reload), so this is a plain Mockito unit
// test: the reload of booking 1 comes back empty, and booking 2 must still expire.
// The real-DB expiry path (detached entities, lazy tickets) is covered by BookingExpirySweepTest.
@ExtendWith(MockitoExtension.class)
class BookingExpiryFailureIsolationTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private EventService eventService;
    @Mock
    private PlatformTransactionManager transactionManager; // TransactionTemplate runs the callback against it
    @Mock
    private QueueService queueService;
    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private BookingService bookingService;

    @Test
    void expireContinuesPastABookingThatFails() {
        bookingService.init(); // @PostConstruct isn't run outside Spring; builds the TransactionTemplate

        Booking vanished = new Booking();
        vanished.setId(1L);

        Ticket ticket = new Ticket();
        ticket.setStatus(TicketStatus.HELD);
        Booking stale = new Booking();
        stale.setId(2L);
        stale.setStatus(BookingStatus.PENDING);
        stale.setTickets(List.of(ticket));
        ticket.setBooking(stale);

        when(bookingRepository.findByStatusAndExpiresAtBefore(eq(BookingStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(vanished, stale));
        when(bookingRepository.findById(1L)).thenReturn(Optional.empty()); // -> NoSuchElementException, caught
        when(bookingRepository.findById(2L)).thenReturn(Optional.of(stale));

        bookingService.expire();

        assertThat(stale.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(ticket.getBooking()).isNull();
    }
}
