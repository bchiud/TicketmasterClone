package com.ticketmaster.event;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EventController.class)
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventRepository eventRepository;

    @Test
    void getAllEventsReturnsEmptyListWhenNoneExist() throws Exception {
        when(eventRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/events"))
               .andExpect(status().isOk())
               .andExpect(content().json("[]"));
    }

    @Test
    void getAllEventsReturnsExistingEvents() throws Exception {
        Event event = new Event();
        event.setId(1L);
        event.setName("Test Concert");
        when(eventRepository.findAll()).thenReturn(List.of(event));

        mockMvc.perform(get("/events"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].name").value("Test Concert"));
    }

    @Test
    void getAllEventsFiltersByNameWhenOnlyNameGiven() throws Exception {
        Event event = new Event();
        event.setId(1L);
        event.setName("Summer Jam");
        when(eventRepository.findByNameContainingIgnoreCase("summer")).thenReturn(List.of(event));

        mockMvc.perform(get("/events").param("name", "summer"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].name").value("Summer Jam"));
    }

    @Test
    void getAllEventsFiltersByStatusWhenOnlyStatusGiven() throws Exception {
        Event event = new Event();
        event.setId(1L);
        event.setStatus(EventStatus.ON_SALE);
        when(eventRepository.findByStatus(EventStatus.ON_SALE)).thenReturn(List.of(event));

        mockMvc.perform(get("/events").param("status", "ON_SALE"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].status").value("ON_SALE"));
    }

    @Test
    void getAllEventsFiltersByNameAndStatusWhenBothGiven() throws Exception {
        Event event = new Event();
        event.setId(1L);
        event.setName("Summer Jam");
        event.setStatus(EventStatus.ON_SALE);
        when(eventRepository.findByNameContainingIgnoreCaseAndStatus("summer", EventStatus.ON_SALE))
                .thenReturn(List.of(event));

        mockMvc.perform(get("/events").param("name", "summer")
                                      .param("status", "ON_SALE"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].name").value("Summer Jam"))
               .andExpect(jsonPath("$[0].status").value("ON_SALE"));
    }

    @Test
    void getEventByIdReturnsEventWhenFound() throws Exception {
        Event event = new Event();
        event.setId(1L);
        event.setName("Test Concert");
        when(eventRepository.findById(1L)).thenReturn(Optional.of(event));

        mockMvc.perform(get("/events/1"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.name").value("Test Concert"));
    }

    @Test
    void getEventByIdReturns404WhenNotFound() throws Exception {
        when(eventRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/events/99"))
               .andExpect(status().isNotFound());
    }
}
