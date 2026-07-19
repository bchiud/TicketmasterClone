package com.ticketmaster.booking;

import com.ticketmaster.booking.exception.InvalidBookingStateException;
import com.ticketmaster.event.Event;
import com.ticketmaster.payment.PaymentService;
import com.ticketmaster.queue.exception.QueueAccessRequiredException;
import com.ticketmaster.seat.Seat;
import com.ticketmaster.ticket.Ticket;
import com.ticketmaster.ticket.TicketStatus;
import com.ticketmaster.ticket.exception.TicketUnavailableException;
import com.ticketmaster.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BookingController.class)
class BookingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BookingRepository bookingRepository;

    @MockitoBean
    private BookingService bookingService;

    @MockitoBean
    private PaymentService paymentService;

    // BookingResponse.from() dereferences event, user and tickets, so fixtures must populate them.
    private static Booking sampleBooking(Long id, BookingStatus status) {
        Event event = new Event();
        event.setId(2L);
        User user = new User();
        user.setId(7L);
        Booking booking = new Booking();
        booking.setId(id);
        booking.setStatus(status);
        booking.setEvent(event);
        booking.setUser(user);
        booking.setTickets(List.of());
        return booking;
    }

    @Test
    void getBookingReturnsBookingWhenFound() throws Exception {
        Booking booking = sampleBooking(1L, BookingStatus.PENDING);
        booking.setTotalCents(5000);
        when(bookingRepository.findWithTicketsById(1L)).thenReturn(Optional.of(booking));

        mockMvc.perform(get("/bookings/1"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.totalCents").value(5000));
    }

    // A booking WITH tickets used to serialize into an infinite Booking<->Ticket cycle (HTTP 200
    // then ~500 levels of nested JSON). The DTO has no back-reference, so this must now be clean.
    @Test
    void getBookingSerializesTicketsWithoutCycle() throws Exception {
        Seat seat = new Seat();
        seat.setId(50L);
        Ticket ticket = new Ticket();
        ticket.setId(10L);
        ticket.setSeat(seat);
        ticket.setStatus(TicketStatus.HELD);
        ticket.setPriceCents(5000);
        Booking booking = sampleBooking(1L, BookingStatus.PENDING);
        booking.setTickets(List.of(ticket));
        when(bookingRepository.findWithTicketsById(1L)).thenReturn(Optional.of(booking));

        mockMvc.perform(get("/bookings/1"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.tickets[0].id").value(10))
               .andExpect(jsonPath("$.tickets[0].seatId").value(50))
               .andExpect(jsonPath("$.tickets[0].status").value("HELD"))
               .andExpect(jsonPath("$.tickets[0].priceCents").value(5000))
               .andExpect(jsonPath("$.tickets[0].booking").doesNotExist()); // no back-reference
    }

    @Test
    void getBookingReturns404WhenNotFound() throws Exception {
        when(bookingRepository.findWithTicketsById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/bookings/99"))
               .andExpect(status().isNotFound());
    }

    @Test
    void getBookingsByUserIdReturnsBookings() throws Exception {
        Booking booking = sampleBooking(1L, BookingStatus.PENDING);
        when(bookingRepository.findWithTicketsByUserId(7L)).thenReturn(List.of(booking));

        mockMvc.perform(get("/users/7/bookings"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void holdBookingReturnsCreatedBooking() throws Exception {
        Booking booking = sampleBooking(1L, BookingStatus.PENDING);
        when(bookingService.hold(anyLong(), anyLong(), any(), any(), any())).thenReturn(booking);

        BookingHoldRequest request = new BookingHoldRequest();
        request.setUserId(1L);
        request.setEventId(2L);
        request.setTicketIds(List.of(3L, 4L));
        request.setIdempotencyKey("idem-1");
        request.setAccessToken("token-1");

        mockMvc.perform(post("/bookings/hold")
                                .contentType("application/json")
                                .content(objectMapper.writeValueAsString(request)))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void holdBookingReturns409WhenTicketUnavailable() throws Exception {
        when(bookingService.hold(anyLong(), anyLong(), any(), any(), any()))
                .thenThrow(new TicketUnavailableException("Ticket 3 unavailable"));

        BookingHoldRequest request = new BookingHoldRequest();
        request.setUserId(1L);
        request.setEventId(2L);
        request.setTicketIds(List.of(3L));
        request.setIdempotencyKey("idem-2");
        request.setAccessToken("token-2");

        mockMvc.perform(post("/bookings/hold")
                                .contentType("application/json")
                                .content(objectMapper.writeValueAsString(request)))
               .andExpect(status().isConflict());
    }

    @Test
    void holdBookingReturns403WhenQueueAccessRequired() throws Exception {
        when(bookingService.hold(anyLong(), anyLong(), any(), any(), any()))
                .thenThrow(new QueueAccessRequiredException("Access Denied"));

        BookingHoldRequest request = new BookingHoldRequest();
        request.setUserId(1L);
        request.setEventId(2L);
        request.setTicketIds(List.of(3L));
        request.setIdempotencyKey("idem-access-denied");
        request.setAccessToken("bogus-token");

        mockMvc.perform(post("/bookings/hold")
                                .contentType("application/json")
                                .content(objectMapper.writeValueAsString(request)))
               .andExpect(status().isForbidden());
    }

    @Test
    void holdBookingReturns400WhenAllFieldsMissing() throws Exception {
        mockMvc.perform(post("/bookings/hold")
                                .contentType("application/json")
                                .content("{}"))
               .andExpect(status().isBadRequest())
               .andExpect(content().string(containsString("userId")))
               .andExpect(content().string(containsString("eventId")))
               .andExpect(content().string(containsString("ticketIds")))
               .andExpect(content().string(containsString("idempotencyKey")));
    }

    @Test
    void holdBookingReturns400WhenTicketIdsIsEmpty() throws Exception {
        BookingHoldRequest request = new BookingHoldRequest();
        request.setUserId(1L);
        request.setEventId(2L);
        request.setTicketIds(List.of());
        request.setIdempotencyKey("idem-3");
        request.setAccessToken("token-3");

        mockMvc.perform(post("/bookings/hold")
                                .contentType("application/json")
                                .content(objectMapper.writeValueAsString(request)))
               .andExpect(status().isBadRequest())
               .andExpect(content().string(containsString("ticketIds")))
               .andExpect(content().string(org.hamcrest.Matchers.not(containsString("userId"))));
    }

    @Test
    void payBookingReturnsConfirmedBooking() throws Exception {
        Booking booking = sampleBooking(1L, BookingStatus.CONFIRMED);
        when(paymentService.pay(1L)).thenReturn(booking);

        mockMvc.perform(post("/bookings/1/pay"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void payBookingReturns404WhenNotFound() throws Exception {
        when(paymentService.pay(99L)).thenThrow(new java.util.NoSuchElementException("Booking not found: 99"));

        mockMvc.perform(post("/bookings/99/pay"))
               .andExpect(status().isNotFound());
    }

    @Test
    void payBookingReturns409WhenBookingNotPending() throws Exception {
        when(paymentService.pay(1L)).thenThrow(new InvalidBookingStateException("Booking not pending"));

        mockMvc.perform(post("/bookings/1/pay"))
               .andExpect(status().isConflict());
    }

    @Test
    void cancelBookingReturnsCancelledBooking() throws Exception {
        Booking booking = sampleBooking(1L, BookingStatus.CANCELLED);
        when(bookingService.cancel(1L)).thenReturn(booking);

        mockMvc.perform(post("/bookings/1/cancel"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancelBookingReturns404WhenNotFound() throws Exception {
        when(bookingService.cancel(99L)).thenThrow(new java.util.NoSuchElementException("Booking not found: 99"));

        mockMvc.perform(post("/bookings/99/cancel"))
               .andExpect(status().isNotFound());
    }

    @Test
    void cancelBookingReturns409WhenBookingNotPending() throws Exception {
        when(bookingService.cancel(1L)).thenThrow(new InvalidBookingStateException("Booking not pending"));

        mockMvc.perform(post("/bookings/1/cancel"))
               .andExpect(status().isConflict());
    }
}
