package com.ticketmaster.payment;

import com.ticketmaster.booking.Booking;
import com.ticketmaster.booking.BookingStatus;
import com.ticketmaster.booking.InvalidBookingState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentRepository paymentRepository;

    @MockitoBean
    private PaymentService paymentService;

    @Test
    void getPaymentReturnsPaymentWhenFound() throws Exception {
        Payment payment = new Payment();
        payment.setId(1L);
        payment.setAmountCents(150);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));

        mockMvc.perform(get("/payments/1"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.amountCents").value(150));
    }

    @Test
    void getPaymentReturns404WhenNotFound() throws Exception {
        when(paymentRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/payments/99"))
               .andExpect(status().isNotFound());
    }

    @Test
    void getPaymentsByBookingIdReturnsPayments() throws Exception {
        Payment payment = new Payment();
        payment.setId(1L);
        when(paymentRepository.findByBookingId(7L)).thenReturn(List.of(payment));

        mockMvc.perform(get("/bookings/7/payments"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void getPaymentsByBookingIdReturnsEmptyListWhenNoneExist() throws Exception {
        when(paymentRepository.findByBookingId(7L)).thenReturn(List.of());

        mockMvc.perform(get("/bookings/7/payments"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void refundBookingReturnsCancelledBooking() throws Exception {
        Booking booking = new Booking();
        booking.setId(1L);
        booking.setStatus(BookingStatus.CANCELLED);
        when(paymentService.refund(1L)).thenReturn(booking);

        mockMvc.perform(post("/bookings/1/refund"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void refundBookingReturns404WhenNotFound() throws Exception {
        when(paymentService.refund(99L)).thenThrow(new NoSuchElementException("Booking not found: 99"));

        mockMvc.perform(post("/bookings/99/refund"))
               .andExpect(status().isNotFound());
    }

    @Test
    void refundBookingReturns409WhenBookingNotConfirmed() throws Exception {
        when(paymentService.refund(1L)).thenThrow(new InvalidBookingState("Booking not confirmed: 1"));

        mockMvc.perform(post("/bookings/1/refund"))
               .andExpect(status().isConflict());
    }
}
