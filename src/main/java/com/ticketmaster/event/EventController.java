package com.ticketmaster.event;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/events")
public class EventController {
    private final EventRepository eventRepository;
    private final EventCancellationService eventCancellationService;
    private final EventService eventService;

    public EventController(EventRepository eventRepository, EventCancellationService eventCancellationService, EventService eventService) {
        this.eventRepository = eventRepository;
        this.eventCancellationService = eventCancellationService;
        this.eventService = eventService;
    }

    @GetMapping("")
    public List<Event> getAllEvents(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) EventStatus status,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String performer,
            @RequestParam(required = false) ZonedDateTime from, @RequestParam(required = false) ZonedDateTime to) {
        return eventRepository.findAll(EventSpecifications.matching(name, status, city, performer, from, to));
    }

    @GetMapping("/{id}")
    public Event getEventById(@PathVariable Long id) {
        return eventRepository.findById(id)
                              .orElseThrow(() -> new NoSuchElementException("Event not found: " + id));
    }

    @PostMapping("")
    @ResponseStatus(HttpStatus.CREATED)
    public Event createEvent(@Valid @RequestBody EventCreateRequest eventCreateRequest) {
        return eventService.createEvent(eventCreateRequest);
    }

    @PostMapping("/{id}/cancel")
    public Event cancelEvent(@PathVariable Long id) {
        return eventCancellationService.cancelEvent(id);
    }
}

