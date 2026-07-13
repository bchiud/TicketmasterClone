package com.ticketmaster.booking;

import com.ticketmaster.event.Event;
import com.ticketmaster.event.EventNotOnSaleException;
import com.ticketmaster.event.EventRepository;
import com.ticketmaster.event.EventService;
import com.ticketmaster.event.EventStatus;
import com.ticketmaster.queue.QueueAccessRequiredException;
import com.ticketmaster.queue.QueueService;
import com.ticketmaster.seat.Seat;
import com.ticketmaster.seat.SeatRepository;
import com.ticketmaster.ticket.Ticket;
import com.ticketmaster.ticket.TicketLimitedExceededException;
import com.ticketmaster.ticket.TicketRepository;
import com.ticketmaster.ticket.TicketStatus;
import com.ticketmaster.ticket.TicketUnavailableException;
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

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({BookingService.class, EventService.class})
class BookingServiceTest {

    @Autowired
    private BookingService bookingService;

    @MockitoBean
    private QueueService queueService;

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

    @Autowired
    private BookingRepository bookingRepository;

    private User saveUser() {
        User user = new User();
        user.setEmail("alice@example.com");
        return userRepository.save(user);
    }

    private Event saveEvent() {
        return saveEvent(EventStatus.ON_SALE);
    }

    private Event saveEvent(EventStatus status) {
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
        event.setStatus(status);
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

    private List<Long> saveTickets(Event event, int count) {
        List<Long> ticketIds = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++)
            ticketIds.add(saveTicket(event, "seat-" + java.util.UUID.randomUUID(), 100).getId());
        return ticketIds;
    }

    @Test
    void holdsAvailableTicketsAndCreatesAPendingBooking() {
        User user = saveUser();
        Event event = saveEvent();
        Ticket ticketA = saveTicket(event, "1", 100);
        Ticket ticketB = saveTicket(event, "2", 250);

        Booking booking = bookingService.hold(
                user.getId(), event.getId(), List.of(ticketA.getId(), ticketB.getId()), "idem-1", null);

        assertThat(booking.getId()).isNotNull();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(booking.getTotalCents()).isEqualTo(350);
        assertThat(booking.getExpiresAt()).isNotNull();

        assertThat(ticketRepository.findById(ticketA.getId()))
                .isPresent()
                .get()
                .extracting(Ticket::getStatus)
                .isEqualTo(TicketStatus.HELD);
        assertThat(ticketRepository.findById(ticketB.getId()))
                .isPresent()
                .get()
                .extracting(Ticket::getStatus)
                .isEqualTo(TicketStatus.HELD);
    }

    @Test
    void retryingWithSameIdempotencyKeyReturnsTheSameBooking() {
        User user = saveUser();
        Event event = saveEvent();
        Ticket ticket = saveTicket(event, "1", 100);

        Booking first = bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "idem-2", null);
        Booking retry = bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "idem-2", null);

        assertThat(retry.getId()).isEqualTo(first.getId());
    }

    @Test
    void throwsWhenATicketIsAlreadyHeld() {
        User user = saveUser();
        Event event = saveEvent();
        Ticket ticket = saveTicket(event, "1", 100);
        bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "idem-3", null);

        assertThatThrownBy(() ->
                bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "idem-4", null))
                .isInstanceOf(TicketUnavailableException.class);
    }

    @Test
    void throwsWhenARequestedTicketIdDoesNotExist() {
        User user = saveUser();
        Event event = saveEvent();

        assertThatThrownBy(() ->
                bookingService.hold(user.getId(), event.getId(), List.of(999L), "idem-5", null))
                .isInstanceOf(TicketUnavailableException.class);
    }

    @Test
    void holdsExactlyUpToTheTicketCapInOneHold() {
        User user = saveUser();
        Event event = saveEvent();
        List<Long> ticketIds = saveTickets(event, 8); // configured cap is 8

        Booking booking = bookingService.hold(user.getId(), event.getId(), ticketIds, "idem-cap-1", null);

        assertThat(booking.getTickets()).hasSize(8);
    }

    @Test
    void throwsWhenRequestingMoreTicketsThanTheCapInOneHold() {
        User user = saveUser();
        Event event = saveEvent();
        List<Long> ticketIds = saveTickets(event, 9); // one over the configured cap of 8

        assertThatThrownBy(() -> bookingService.hold(user.getId(), event.getId(), ticketIds, "idem-cap-2", null))
                .isInstanceOf(TicketLimitedExceededException.class);
    }

    @Test
    void throwsWhenExistingHoldsPlusNewRequestExceedTheCap() {
        User user = saveUser();
        Event event = saveEvent();
        List<Long> firstEight = saveTickets(event, 8);
        bookingService.hold(user.getId(), event.getId(), firstEight, "idem-cap-3a", null);

        List<Long> oneMore = saveTickets(event, 1);

        assertThatThrownBy(() -> bookingService.hold(user.getId(), event.getId(), oneMore, "idem-cap-3b", null))
                .isInstanceOf(TicketLimitedExceededException.class);
    }

    @Test
    void throwsWhenEventIsNotOnSale() {
        User user = saveUser();
        Event event = saveEvent(EventStatus.SCHEDULED);
        Ticket ticket = saveTicket(event, "1", 100);

        assertThatThrownBy(() ->
                bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "idem-not-on-sale", null))
                .isInstanceOf(EventNotOnSaleException.class);
    }

    @Test
    void holdsSucceedsWhenEventRequiresQueueAndAccessGranted() {
        User user = saveUser();
        Event event = saveEvent();
        event.setRequiresQueue(true);
        eventRepository.save(event);
        Ticket ticket = saveTicket(event, "1", 100);
        when(queueService.hasAccess(event.getId(), "valid-token")).thenReturn(true);

        Booking booking = bookingService.hold(
                user.getId(), event.getId(), List.of(ticket.getId()), "idem-queue-1", "valid-token");

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void throwsWhenEventRequiresQueueAndAccessDenied() {
        User user = saveUser();
        Event event = saveEvent();
        event.setRequiresQueue(true);
        eventRepository.save(event);
        Ticket ticket = saveTicket(event, "1", 100);
        when(queueService.hasAccess(event.getId(), "bad-token")).thenReturn(false);

        assertThatThrownBy(() -> bookingService.hold(
                user.getId(), event.getId(), List.of(ticket.getId()), "idem-queue-2", "bad-token"))
                .isInstanceOf(QueueAccessRequiredException.class);
    }

    @Test
    void throwsWhenEventRequiresQueueAndAccessTokenMissing() {
        User user = saveUser();
        Event event = saveEvent();
        event.setRequiresQueue(true);
        eventRepository.save(event);
        Ticket ticket = saveTicket(event, "1", 100);

        assertThatThrownBy(() -> bookingService.hold(
                user.getId(), event.getId(), List.of(ticket.getId()), "idem-queue-3", null))
                .isInstanceOf(QueueAccessRequiredException.class);
    }

    @Test
    void throwsWhenEventDoesNotExist() {
        User user = saveUser();

        assertThatThrownBy(() ->
                bookingService.hold(user.getId(), 999L, List.of(1L), "idem-no-event", null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void marksEventSoldOutWhenLastAvailableTicketIsConfirmed() {
        User user = saveUser();
        Event event = saveEvent();
        Ticket ticket = saveTicket(event, "1", 100);
        Booking booking = bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "idem-soldout-1", null);

        bookingService.confirm(booking.getId());

        assertThat(eventRepository.findById(event.getId()))
                .isPresent()
                .get()
                .extracting(Event::getStatus)
                .isEqualTo(EventStatus.SOLD_OUT);
    }

    @Test
    void doesNotMarkEventSoldOutWhileTicketsRemainAvailable() {
        User user = saveUser();
        Event event = saveEvent();
        Ticket ticketA = saveTicket(event, "1", 100);
        saveTicket(event, "2", 100); // left AVAILABLE
        Booking booking = bookingService.hold(user.getId(), event.getId(), List.of(ticketA.getId()), "idem-soldout-2", null);

        bookingService.confirm(booking.getId());

        assertThat(eventRepository.findById(event.getId()))
                .isPresent()
                .get()
                .extracting(Event::getStatus)
                .isEqualTo(EventStatus.ON_SALE);
    }

    @Test
    void throwsWhenUserDoesNotExist() {
        Event event = saveEvent();
        Ticket ticket = saveTicket(event, "1", 100);

        assertThatThrownBy(() ->
                bookingService.hold(999L, event.getId(), List.of(ticket.getId()), "idem-6", null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void cancelsAPendingBookingAndReleasesItsTickets() {
        User user = saveUser();
        Event event = saveEvent();
        Ticket ticket = saveTicket(event, "1", 100);
        Booking booking = bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "idem-cancel-1", null);

        Booking cancelled = bookingService.cancel(booking.getId());

        assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(ticketRepository.findById(ticket.getId()))
                .isPresent()
                .get()
                .extracting(Ticket::getStatus)
                .isEqualTo(TicketStatus.AVAILABLE);
    }

    @Test
    void cancelsAConfirmedBookingAndReleasesItsTickets() {
        User user = saveUser();
        Event event = saveEvent();
        Ticket ticket = saveTicket(event, "1", 100);
        Booking booking = bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "idem-cancel-2", null);
        bookingService.confirm(booking.getId());

        Booking cancelled = bookingService.cancel(booking.getId());

        assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(ticketRepository.findById(ticket.getId()))
                .isPresent()
                .get()
                .extracting(Ticket::getStatus)
                .isEqualTo(TicketStatus.AVAILABLE);
    }

    @Test
    void throwsWhenCancellingAnAlreadyCancelledBooking() {
        User user = saveUser();
        Event event = saveEvent();
        Ticket ticket = saveTicket(event, "1", 100);
        Booking booking = bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "idem-cancel-3", null);
        bookingService.cancel(booking.getId());

        assertThatThrownBy(() -> bookingService.cancel(booking.getId()))
                .isInstanceOf(InvalidBookingState.class);
    }

    @Test
    void throwsWhenCancellingANonexistentBooking() {
        assertThatThrownBy(() -> bookingService.cancel(999L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void confirmsAPendingBookingAndBooksItsTickets() {
        User user = saveUser();
        Event event = saveEvent();
        Ticket ticket = saveTicket(event, "1", 100);
        Booking booking = bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "idem-confirm-1", null);

        Booking confirmed = bookingService.confirm(booking.getId());

        assertThat(confirmed.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(ticketRepository.findById(ticket.getId()))
                .isPresent()
                .get()
                .extracting(Ticket::getStatus)
                .isEqualTo(TicketStatus.BOOKED);
    }

    @Test
    void throwsWhenConfirmingABookingThatIsNotPending() {
        User user = saveUser();
        Event event = saveEvent();
        Ticket ticket = saveTicket(event, "1", 100);
        Booking booking = bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "idem-confirm-2", null);
        bookingService.confirm(booking.getId());

        assertThatThrownBy(() -> bookingService.confirm(booking.getId()))
                .isInstanceOf(InvalidBookingState.class);
    }

    @Test
    void throwsWhenConfirmingAnExpiredBooking() {
        User user = saveUser();
        Event event = saveEvent();
        Ticket ticket = saveTicket(event, "1", 100);
        Booking booking = bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "idem-confirm-3", null);
        booking.setExpiresAt(Instant.now()
                                     .minusSeconds(60));
        bookingRepository.saveAndFlush(booking);

        assertThatThrownBy(() -> bookingService.confirm(booking.getId()))
                .isInstanceOf(InvalidBookingState.class);
    }

    @Test
    void throwsWhenConfirmingANonexistentBooking() {
        assertThatThrownBy(() -> bookingService.confirm(999L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void expiresStaleBookingsAndReleasesTheirTickets() {
        User user = saveUser();
        Event event = saveEvent();
        Ticket ticket = saveTicket(event, "1", 100);
        Booking booking = bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "idem-7", null);
        booking.setExpiresAt(Instant.now()
                                     .minusSeconds(60));
        bookingRepository.saveAndFlush(booking);

        bookingService.expire();

        assertThat(bookingRepository.findById(booking.getId()))
                .isPresent()
                .get()
                .extracting(Booking::getStatus)
                .isEqualTo(BookingStatus.EXPIRED);
        assertThat(ticketRepository.findById(ticket.getId()))
                .isPresent()
                .get()
                .extracting(Ticket::getStatus)
                .isEqualTo(TicketStatus.AVAILABLE);
    }

    @Test
    void doesNotExpireBookingsStillWithinTheirHoldWindow() {
        User user = saveUser();
        Event event = saveEvent();
        Ticket ticket = saveTicket(event, "1", 100);
        Booking booking = bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "idem-8", null);

        bookingService.expire();

        assertThat(bookingRepository.findById(booking.getId()))
                .isPresent()
                .get()
                .extracting(Booking::getStatus)
                .isEqualTo(BookingStatus.PENDING);
        assertThat(ticketRepository.findById(ticket.getId()))
                .isPresent()
                .get()
                .extracting(Ticket::getStatus)
                .isEqualTo(TicketStatus.HELD);
    }

    @Test
    void doesNotExpireAlreadyConfirmedBookings() {
        User user = saveUser();
        Event event = saveEvent();
        Ticket ticket = saveTicket(event, "1", 100);
        Booking booking = bookingService.hold(user.getId(), event.getId(), List.of(ticket.getId()), "idem-9", null);
        bookingService.confirm(booking.getId());
        booking.setExpiresAt(Instant.now()
                                     .minusSeconds(60));
        bookingRepository.saveAndFlush(booking);

        bookingService.expire();

        assertThat(bookingRepository.findById(booking.getId()))
                .isPresent()
                .get()
                .extracting(Booking::getStatus)
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(ticketRepository.findById(ticket.getId()))
                .isPresent()
                .get()
                .extracting(Ticket::getStatus)
                .isEqualTo(TicketStatus.BOOKED);
    }
}
