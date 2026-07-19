package com.ticketmaster.booking;

import com.ticketmaster.ticket.Ticket;
import com.ticketmaster.ticket.TicketStatus;

public record TicketSummary(Long id, Long seatId, TicketStatus status, Integer priceCents) {
    public static TicketSummary from(Ticket t) {
        return new TicketSummary(t.getId(),
                                 t.getSeat()
                                  .getId(),
                                 t.getStatus(),
                                 t.getPriceCents());
    }
}