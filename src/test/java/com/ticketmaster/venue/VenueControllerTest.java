package com.ticketmaster.venue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VenueController.class)
class VenueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VenueRepository venueRepository;

    @Test
    void getAllVenuesReturnsEmptyListWhenNoneExist() throws Exception {
        when(venueRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/venues"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void getAllVenuesReturnsExistingVenues() throws Exception {
        Venue venue = new Venue();
        venue.setId(1L);
        venue.setName("Chase Center");
        when(venueRepository.findAll()).thenReturn(List.of(venue));

        mockMvc.perform(get("/venues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Chase Center"));
    }

    @Test
    void getVenueByIdReturnsVenueWhenFound() throws Exception {
        Venue venue = new Venue();
        venue.setId(1L);
        venue.setName("Chase Center");
        when(venueRepository.findById(1L)).thenReturn(Optional.of(venue));

        mockMvc.perform(get("/venues/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Chase Center"));
    }

    @Test
    void getVenueByIdReturns404WhenNotFound() throws Exception {
        when(venueRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/venues/99"))
                .andExpect(status().isNotFound());
    }
}
