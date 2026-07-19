package com.ticketmaster.payment;

import com.ticketmaster.booking.BookingService;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Full-context, real-DB, real request path with open-in-view=false. The idempotent-pay guard
// returns a booking loaded outside confirm(); if its lazy `tickets` collection isn't fetched,
// mapping to BookingResponse throws LazyInitializationException -> 500. A @DataJpaTest (open
// session) or a mocked @WebMvcTest can't catch that; only driving pay() twice through the
// controller can. @AfterEach deletes only this test's rows (runs even on failure).
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "booking.expiry-sweep-interval-ms=3600000",
        "event.on-sale-sweep-interval-ms=3600000",
        "queue.admit-interval-ms=3600000"
})
class PaymentIdempotencyRealPathTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private BookingService bookingService;
    @Autowired private com.ticketmaster.booking.BookingRepository bookingRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private VenueRepository venueRepository;
    @Autowired private UserRepository userRepository;

    private Long bookingId, ticketId, eventId, seatId, venueId, userId;

    @Test
    void payingTwiceThroughTheApiIsIdempotentUnderOsivOff() throws Exception {
        seed();
        bookingId = bookingService.hold(userId, eventId, List.of(ticketId), "idem-realpath-" + UUID.randomUUID(), null)
                                  .getId();

        // first pay confirms
        mockMvc.perform(post("/bookings/" + bookingId + "/pay"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // retry: the guard's early return must serialize cleanly (200), not LazyInit -> 500
        mockMvc.perform(post("/bookings/" + bookingId + "/pay"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("CONFIRMED"))
               .andExpect(jsonPath("$.tickets[0].status").value("BOOKED"));

        // no second charge
        assertThat(paymentRepository.findByBookingId(bookingId)).hasSize(1);
    }

    private void seed() {
        User user = new User();
        user.setEmail("pay-idem-" + UUID.randomUUID() + "@example.com");
        userId = userRepository.save(user).getId();

        Venue venue = new Venue();
        venue.setName("Idem Venue");
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
        ticket.setStatus(TicketStatus.AVAILABLE);
        ticketId = ticketRepository.save(ticket).getId();
    }

    @AfterEach
    void cleanup() {
        if (bookingId != null) paymentRepository.findByBookingId(bookingId).forEach(paymentRepository::delete);
        if (ticketId != null) ticketRepository.deleteById(ticketId);
        if (bookingId != null) bookingRepository.deleteById(bookingId);
        if (eventId != null) eventRepository.deleteById(eventId);
        if (seatId != null) seatRepository.deleteById(seatId);
        if (venueId != null) venueRepository.deleteById(venueId);
        if (userId != null) userRepository.deleteById(userId);
    }
}
