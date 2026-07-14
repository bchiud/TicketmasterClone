package com.ticketmaster.venue;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

    private ResultActions postVenue(String json) throws Exception {
        return mockMvc.perform(post("/venues").contentType(MediaType.APPLICATION_JSON)
                                              .content(json));
    }

    @Test
    void createVenueReturns201AndPersistsTheVenue() throws Exception {
        Venue saved = new Venue();
        saved.setId(7L);
        saved.setName("The Fillmore");
        saved.setAddress("1805 Geary Blvd");
        saved.setCity("San Francisco");
        when(venueRepository.save(any(Venue.class))).thenReturn(saved);

        postVenue("""
                  {"name":"The Fillmore","address":"1805 Geary Blvd","city":"San Francisco"}
                  """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("The Fillmore"))
                .andExpect(jsonPath("$.city").value("San Francisco"));
    }

    // the id is @GeneratedValue, so a client-supplied one must never be honoured
    @Test
    void createVenueIgnoresAClientSuppliedId() throws Exception {
        ArgumentCaptor<Venue> captor = ArgumentCaptor.forClass(Venue.class);
        when(venueRepository.save(any(Venue.class))).thenAnswer(inv -> inv.getArgument(0));

        postVenue("""
                  {"id":99,"name":"The Fillmore","address":"1805 Geary Blvd","city":"San Francisco"}
                  """)
                .andExpect(status().isCreated());

        verify(venueRepository).save(captor.capture());
        assertThat(captor.getValue()
                         .getId()).isNull();
    }

    @Test
    void createVenueRejectsMissingFields() throws Exception {
        postVenue("{}").andExpect(status().isBadRequest())
                       .andExpect(content().string(containsString("name")))
                       .andExpect(content().string(containsString("address")))
                       .andExpect(content().string(containsString("city")));

        verify(venueRepository, never()).save(any(Venue.class));
    }

    @Test
    void createVenueRejectsNullFields() throws Exception {
        postVenue("""
                  {"name":null,"address":null,"city":null}
                  """)
                .andExpect(status().isBadRequest());

        verify(venueRepository, never()).save(any(Venue.class));
    }

    // @NotBlank, not @NotNull: an empty or whitespace-only name is as useless as a missing one
    @Test
    void createVenueRejectsBlankAndWhitespaceOnlyFields() throws Exception {
        postVenue("""
                  {"name":"","address":"","city":""}
                  """)
                .andExpect(status().isBadRequest());

        postVenue("""
                  {"name":"   ","address":"  ","city":" "}
                  """)
                .andExpect(status().isBadRequest());

        verify(venueRepository, never()).save(any(Venue.class));
    }
}
