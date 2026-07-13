package com.ticketmaster.ticket;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TicketService {
    @Autowired
    private TicketRepository ticketRepository;

    public void cancelTicketsbyEventId(Long eventId) {
        List<Ticket> tickets = ticketRepository.findByEventId(eventId);
        for (Ticket ticket : tickets)
            ticket.setStatus(TicketStatus.CANCELLED);
        ticketRepository.saveAll(tickets);
    }
}
