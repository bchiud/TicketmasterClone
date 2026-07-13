package com.ticketmaster.booking;

import tools.jackson.databind.ObjectMapper;
import com.ticketmaster.payment.PaymentService;
import com.ticketmaster.queue.QueueAccessRequiredException;
import com.ticketmaster.ticket.TicketUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @Test
    void getBookingReturnsBookingWhenFound() throws Exception {
        Booking booking = new Booking();
        booking.setId(1L);
        booking.setTotalCents(5000);
        when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));

        mockMvc.perform(get("/bookings/1"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.totalCents").value(5000));
    }

    @Test
    void getBookingReturns404WhenNotFound() throws Exception {
        when(bookingRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/bookings/99"))
               .andExpect(status().isNotFound());
    }

    @Test
    void getBookingsByUserIdReturnsBookings() throws Exception {
        Booking booking = new Booking();
        booking.setId(1L);
        when(bookingRepository.findByUserId(7L)).thenReturn(List.of(booking));

        mockMvc.perform(get("/users/7/bookings"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void holdBookingReturnsCreatedBooking() throws Exception {
        Booking booking = new Booking();
        booking.setId(1L);
        booking.setStatus(BookingStatus.PENDING);
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
        Booking booking = new Booking();
        booking.setId(1L);
        booking.setStatus(BookingStatus.CONFIRMED);
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
        when(paymentService.pay(1L)).thenThrow(new InvalidBookingState("Booking not pending"));

        mockMvc.perform(post("/bookings/1/pay"))
               .andExpect(status().isConflict());
    }

    @Test
    void cancelBookingReturnsCancelledBooking() throws Exception {
        Booking booking = new Booking();
        booking.setId(1L);
        booking.setStatus(BookingStatus.CANCELLED);
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
        when(bookingService.cancel(1L)).thenThrow(new InvalidBookingState("Booking not pending"));

        mockMvc.perform(post("/bookings/1/cancel"))
               .andExpect(status().isConflict());
    }
}
