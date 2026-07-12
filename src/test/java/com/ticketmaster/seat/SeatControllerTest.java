package com.ticketmaster.seat;

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

@WebMvcTest(SeatController.class)
class SeatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SeatRepository seatRepository;

    @Test
    void getSeatReturnsSeatWhenFound() throws Exception {
        Seat seat = new Seat();
        seat.setId(1L);
        seat.setSection("A");
        when(seatRepository.findById(1L)).thenReturn(Optional.of(seat));

        mockMvc.perform(get("/seats/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.section").value("A"));
    }

    @Test
    void getSeatReturns404WhenNotFound() throws Exception {
        when(seatRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/seats/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getSeatsReturnsSeatsForVenue() throws Exception {
        Seat seat = new Seat();
        seat.setId(1L);
        seat.setSection("B");
        when(seatRepository.findByVenueId(5L)).thenReturn(List.of(seat));

        mockMvc.perform(get("/venues/5/seats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].section").value("B"));
    }
}
