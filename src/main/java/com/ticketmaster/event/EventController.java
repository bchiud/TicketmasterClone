package com.ticketmaster.event;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/events")
public class EventController {
    private final EventRepository eventRepository;
    private final EventCancellationService eventCancellationService;
    private final EventService eventService;

    public EventController(EventRepository eventRepository,
                           EventCancellationService eventCancellationService,
                           EventService eventService) {
        this.eventRepository = eventRepository;
        this.eventCancellationService = eventCancellationService;
        this.eventService = eventService;
    }

    @GetMapping("")
    public Page<EventResponse> getAllEvents(@RequestParam(required = false) String name,
                                            @RequestParam(required = false) EventStatus status,
                                            @RequestParam(required = false) String city,
                                            @RequestParam(required = false) String performer,
                                            @RequestParam(required = false) ZonedDateTime from,
                                            @RequestParam(required = false) ZonedDateTime to,
                                            @PageableDefault(size = 20, sort = "startsAt") Pageable pageable) {
        return eventRepository.findAll(EventSpecifications.matching(name, status, city, performer, from, to), pageable)
                              .map(EventResponse::from);
    }

    @GetMapping("/{id}")
    public EventResponse getEventById(@PathVariable Long id) {
        return EventResponse.from(eventRepository.findById(id)
                                                 .orElseThrow(() -> new NoSuchElementException(
                                                         "Event not found: " + id)));
    }

    @PostMapping("")
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse createEvent(@Valid @RequestBody EventCreateRequest eventCreateRequest) {
        return EventResponse.from(eventService.createEvent(eventCreateRequest));
    }

    @PostMapping("/{id}/cancel")
    public EventResponse cancelEvent(@PathVariable Long id) {
        return EventResponse.from(eventCancellationService.cancelEvent(id));
    }
}

