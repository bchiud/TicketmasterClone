package com.ticketmaster.ticket;

public record TicketResponse(Long id, Long eventId, Long seatId, TicketStatus status, int priceCents, Long bookingId) {
    // no booking field so we don't get a cycle when generating the json response:
    // Booking → tickets[0] → booking → tickets[0] → booking → tickets[0] → etc.

    public static TicketResponse from(Ticket t) {
        return new TicketResponse(t.getId(),
                                  t.getEvent().getId(),
                                  t.getSeat().getId(),
                                  t.getStatus(),
                                  t.getPriceCents(),
                                  t.getBooking() == null ? null : t.getBooking().getId());
    }
}
