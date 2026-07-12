package com.ticketmaster.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BookingController.class)
class BookingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookingRepository bookingRepository;

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
}
