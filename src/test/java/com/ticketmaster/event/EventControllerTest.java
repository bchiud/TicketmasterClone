package com.ticketmaster.event;

import com.ticketmaster.venue.VenueHasNoSeatsException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EventController.class)
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventRepository eventRepository;

    @MockitoBean
    private EventCancellationService eventCancellationService;

    @MockitoBean
    private EventService eventService;

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

    @Test
    void cancelEventReturnsCancelledEvent() throws Exception {
        Event event = new Event();
        event.setId(1L);
        event.setStatus(EventStatus.CANCELLED);
        when(eventCancellationService.cancelEvent(1L)).thenReturn(event);

        mockMvc.perform(post("/events/1/cancel"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancelEventReturns409WhenAlreadyCancelled() throws Exception {
        when(eventCancellationService.cancelEvent(1L))
                .thenThrow(new EventAlreadyCancelledException("Event already cancelled"));

        mockMvc.perform(post("/events/1/cancel"))
               .andExpect(status().isConflict());
    }

    @Test
    void cancelEventReturns404WhenNotFound() throws Exception {
        when(eventCancellationService.cancelEvent(99L))
                .thenThrow(new NoSuchElementException("Event not found: 99"));

        mockMvc.perform(post("/events/99/cancel"))
               .andExpect(status().isNotFound());
    }

    private static final String VALID_CREATE_BODY = """
            {"name":"Fan-out Fest","performer":"The Openers","venueId":1,
             "startsAt":"2026-09-01T19:00:00Z","onSaleAt":"2026-07-01T10:00:00Z",
             "priceCents":8000,"requiresQueue":true}
            """;

    @Test
    void createEventReturns201AndTheCreatedEvent() throws Exception {
        Event created = new Event();
        created.setId(5L);
        created.setName("Fan-out Fest");
        when(eventService.createEvent(any(EventCreateRequest.class))).thenReturn(created);

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                                       .content(VALID_CREATE_BODY))
               .andExpect(status().isCreated())
               .andExpect(jsonPath("$.id").value(5))
               .andExpect(jsonPath("$.name").value("Fan-out Fest"));
    }

    @Test
    void createEventRejectsMissingFields() throws Exception {
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                                       .content("{}"))
               .andExpect(status().isBadRequest());

        verify(eventService, never()).createEvent(any());
    }

    @Test
    void createEventRejectsNonPositivePrice() throws Exception {
        String zeroPrice = VALID_CREATE_BODY.replace("\"priceCents\":8000", "\"priceCents\":0");

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                                       .content(zeroPrice))
               .andExpect(status().isBadRequest());

        verify(eventService, never()).createEvent(any());
    }

    // the service throws for an unknown venue; the controller must surface it as 404 via ApiExceptionHandler
    @Test
    void createEventReturns404WhenVenueNotFound() throws Exception {
        when(eventService.createEvent(any(EventCreateRequest.class)))
                .thenThrow(new NoSuchElementException("Venue not found: 1"));

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                                       .content(VALID_CREATE_BODY))
               .andExpect(status().isNotFound());
    }

    // ...and a seatless venue as 400
    @Test
    void createEventReturns400WhenVenueHasNoSeats() throws Exception {
        when(eventService.createEvent(any(EventCreateRequest.class)))
                .thenThrow(new VenueHasNoSeatsException("Venue has no seats: 1"));

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                                       .content(VALID_CREATE_BODY))
               .andExpect(status().isBadRequest());
    }
}
