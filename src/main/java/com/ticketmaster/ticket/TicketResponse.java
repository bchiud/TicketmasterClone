package com.ticketmaster.ticket;

public record TicketResponse(Long id, Long eventId, Long seatId, TicketStatus status, int priceCents, Long bookingId) {
    public static TicketResponse from(Ticket t) {
        return new TicketResponse(t.getId(),
                                  t.getEvent()
                                   .getId(),
                                  t.getSeat()
                                   .getId(),
                                  t.getStatus(),
                                  t.getPriceCents(),
                                  t.getBooking() == null
                                  ? null
                                  : t.getBooking()
                                     .getId());
    }
}
