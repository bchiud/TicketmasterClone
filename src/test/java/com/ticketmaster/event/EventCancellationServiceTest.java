package com.ticketmaster.event;

import com.ticketmaster.booking.Booking;
import com.ticketmaster.booking.BookingRepository;
import com.ticketmaster.booking.BookingService;
import com.ticketmaster.booking.BookingStatus;
import com.ticketmaster.payment.PaymentRepository;
import com.ticketmaster.payment.PaymentService;
import com.ticketmaster.payment.PaymentStatus;
import com.ticketmaster.queue.QueueService;
import com.ticketmaster.seat.Seat;
import com.ticketmaster.seat.SeatRepository;
import com.ticketmaster.ticket.Ticket;
import com.ticketmaster.ticket.TicketRepository;
import com.ticketmaster.ticket.TicketService;
import com.ticketmaster.ticket.TicketStatus;
import com.ticketmaster.user.User;
import com.ticketmaster.user.UserRepository;
import com.ticketmaster.venue.Venue;
import com.ticketmaster.venue.VenueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EventCancellationService.class, BookingService.class, PaymentService.class, EventService.class, TicketService.class})
class EventCancellationServiceTest {

    @Autowired
    private EventCancellationService eventCancellationService;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private QueueService queueService;

    private User saveUser() {
        User user = new User();
        user.setEmail("cancel-" + UUID.randomUUID() + "@example.com");
        return userRepository.save(user);
    }

    private Event saveEvent() {
        Venue venue = new Venue();
        venue.setName("Madison Square Garden");
        venue.setCity("New York");
        venue.setAddress("4 Pennsylvania Plaza");
        venueRepository.save(venue);

        Event event = new Event();
        event.setName("Test Concert");
        event.setVenue(venue);
        event.setStartsAt(ZonedDateTime.now()
                                       .plusDays(30));
        event.setStatus(EventStatus.ON_SALE);
        return eventRepository.save(event);
    }

    private Ticket saveTicket(Event event, String seatNumber) {
        Seat seat = new Seat();
        seat.setVenue(event.getVenue());
        seat.setSection("A");
        seat.setRowLabel("1");
        seat.setSeatNumber(seatNumber);
        seatRepository.save(seat);

        Ticket ticket = new Ticket();
        ticket.setEvent(event);
        ticket.setSeat(seat);
        ticket.setPriceCents(100);
        return ticketRepository.save(ticket);
    }

    @Test
    void cancelEventFlipsEventStatusToCancelled() {
        Event event = saveEvent();

        Event cancelled = eventCancellationService.cancelEvent(event.getId());

        assertThat(cancelled.getStatus()).isEqualTo(EventStatus.CANCELLED);
        assertThat(eventRepository.findById(event.getId()))
                .isPresent()
                .get()
                .extracting(Event::getStatus)
                .isEqualTo(EventStatus.CANCELLED);
    }

    @Test
    void cancelEventCancelsPendingBookingsAndReleasesNothingBackToAvailable() {
        User user = saveUser();
        Event event = saveEvent();
        Ticket ticket = saveTicket(event, "1");
        Booking pending = bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "idem-1", null);

        eventCancellationService.cancelEvent(event.getId());

        assertThat(bookingRepository.findById(pending.getId()))
                .isPresent()
                .get()
                .extracting(Booking::getStatus)
                .isEqualTo(BookingStatus.CANCELLED);
        assertThat(ticketRepository.findById(ticket.getId()))
                .isPresent()
                .get()
                .extracting(Ticket::getStatus)
                .isEqualTo(TicketStatus.CANCELLED);
    }

    @Test
    void cancelEventRefundsConfirmedBookings() {
        User user = saveUser();
        Event event = saveEvent();
        Ticket ticket = saveTicket(event, "1");
        Booking booking = bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "idem-2", null);
        paymentService.pay(booking.getId());

        eventCancellationService.cancelEvent(event.getId());

        assertThat(bookingRepository.findById(booking.getId()))
                .isPresent()
                .get()
                .extracting(Booking::getStatus)
                .isEqualTo(BookingStatus.CANCELLED);
        assertThat(paymentRepository.findByBookingId(booking.getId()))
                .extracting(payment -> payment.getStatus())
                .contains(PaymentStatus.REFUNDED);
        assertThat(ticketRepository.findById(ticket.getId()))
                .isPresent()
                .get()
                .extracting(Ticket::getStatus)
                .isEqualTo(TicketStatus.CANCELLED);
    }

    @Test
    void cancelEventCancelsTicketsThatWereNeverHeldOrBooked() {
        Event event = saveEvent();
        Ticket untouchedTicket = saveTicket(event, "1");

        eventCancellationService.cancelEvent(event.getId());

        assertThat(ticketRepository.findById(untouchedTicket.getId()))
                .isPresent()
                .get()
                .extracting(Ticket::getStatus)
                .isEqualTo(TicketStatus.CANCELLED);
    }

    @Test
    void throwsWhenCancellingAnAlreadyCancelledEvent() {
        Event event = saveEvent();
        eventCancellationService.cancelEvent(event.getId());

        assertThatThrownBy(() -> eventCancellationService.cancelEvent(event.getId()))
                .isInstanceOf(EventAlreadyCancelledException.class);
    }

    @Test
    void throwsWhenCancellingANonexistentEvent() {
        assertThatThrownBy(() -> eventCancellationService.cancelEvent(999L))
                .isInstanceOf(NoSuchElementException.class);
    }
}
