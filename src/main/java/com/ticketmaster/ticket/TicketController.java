package com.ticketmaster.ticket;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
public class TicketController {
    private final TicketRepository ticketRepository;

    public TicketController(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @GetMapping("/tickets/{id}")
    public Ticket getTicketById(@PathVariable Long id) {
        return ticketRepository.findById(id)
                               .orElseThrow(() -> new NoSuchElementException("Ticket not found: " + id));
    }

    @GetMapping("/events/{eventId}/tickets")
    public List<Ticket> getTicketsForEvent(@PathVariable Long eventId,
                                           @RequestParam(required = false) TicketStatus status) {
        if (status != null) return ticketRepository.findByEventIdAndStatus(eventId, status);
        return ticketRepository.findByEventId(eventId);
    }
}
