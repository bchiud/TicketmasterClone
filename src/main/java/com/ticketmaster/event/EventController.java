package com.ticketmaster.event;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/events")
public class EventController {
    private final EventRepository eventRepository;

    public EventController(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @GetMapping
    public List<Event> getAllEvents(@RequestParam(required = false) String name,
                                    @RequestParam(required = false) EventStatus status) {
        if (name == null && status == null)
            return eventRepository.findAll();
        else if (name == null)
            return eventRepository.findByStatus(status);
        else if (status == null)
            return eventRepository.findByNameContainingIgnoreCase(name);
        else
            return eventRepository.findByNameContainingIgnoreCaseAndStatus(name, status);
    }

    @GetMapping("/{id}")
    public Event getEventById(@PathVariable Long id) {
        return eventRepository.findById(id)
                              .orElseThrow(() -> new RuntimeException("Event not found: " + id));
    }
}

