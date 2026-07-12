package com.ticketmaster.event;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    void getEventByIdThrowsWhenNotFound() {
        // No @ExceptionHandler exists yet, so the controller's RuntimeException
        // propagates out of MockMvc instead of becoming a 404/500 response.
        when(eventRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ServletException.class, () -> mockMvc.perform(get("/events/99")));
    }
}
