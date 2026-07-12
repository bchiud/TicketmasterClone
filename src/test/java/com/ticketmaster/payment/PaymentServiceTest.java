package com.ticketmaster.payment;

import com.ticketmaster.booking.Booking;
import com.ticketmaster.booking.BookingRepository;
import com.ticketmaster.booking.BookingService;
import com.ticketmaster.booking.BookingStatus;
import com.ticketmaster.booking.InvalidBookingState;
import com.ticketmaster.event.Event;
import com.ticketmaster.event.EventRepository;
import com.ticketmaster.seat.Seat;
import com.ticketmaster.seat.SeatRepository;
import com.ticketmaster.ticket.Ticket;
import com.ticketmaster.ticket.TicketRepository;
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

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BookingService.class, PaymentService.class})
class PaymentServiceTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private TicketRepository ticketRepository;

    private User saveUser() {
        User user = new User();
        user.setEmail("alice@example.com");
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
        return eventRepository.save(event);
    }

    private Ticket saveTicket(Event event, String seatNumber, int priceCents) {
        Seat seat = new Seat();
        seat.setVenue(event.getVenue());
        seat.setSection("A");
        seat.setRowLabel("1");
        seat.setSeatNumber(seatNumber);
        seatRepository.save(seat);

        Ticket ticket = new Ticket();
        ticket.setEvent(event);
        ticket.setSeat(seat);
        ticket.setPriceCents(priceCents);
        return ticketRepository.save(ticket);
    }

    @Test
    void paysForAPendingBookingAndConfirmsIt() {
        User user = saveUser();
        Event event = saveEvent();
        Ticket ticket = saveTicket(event, "1", 150);
        Booking booking = bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "pay-idem-1");

        Booking confirmed = paymentService.pay(booking.getId());

        assertThat(confirmed.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(ticketRepository.findById(ticket.getId()))
                .isPresent()
                .get()
                .extracting(Ticket::getStatus)
                .isEqualTo(TicketStatus.BOOKED);

        assertThat(paymentRepository.findByBookingId(booking.getId()))
                .hasSize(1)
                .first()
                .satisfies(payment -> {
                    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
                    assertThat(payment.getAmountCents()).isEqualTo(150);
                    assertThat(payment.getProviderRef()).startsWith("fake-");
                });
    }

    @Test
    void throwsWhenPayingForANonexistentBooking() {
        assertThatThrownBy(() -> paymentService.pay(999L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void throwsWhenPayingForAnAlreadyConfirmedBooking() {
        User user = saveUser();
        Event event = saveEvent();
        Ticket ticket = saveTicket(event, "1", 150);
        Booking booking = bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "pay-idem-2");
        paymentService.pay(booking.getId());

        assertThatThrownBy(() -> paymentService.pay(booking.getId()))
                .isInstanceOf(InvalidBookingState.class);
    }

    @Test
    void throwsWhenPayingForAnExpiredBooking() {
        User user = saveUser();
        Event event = saveEvent();
        Ticket ticket = saveTicket(event, "1", 150);
        Booking booking = bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "pay-idem-3");
        booking.setExpiresAt(Instant.now()
                                     .minusSeconds(60));
        bookingRepository.saveAndFlush(booking);

        assertThatThrownBy(() -> paymentService.pay(booking.getId()))
                .isInstanceOf(InvalidBookingState.class);
    }
}
