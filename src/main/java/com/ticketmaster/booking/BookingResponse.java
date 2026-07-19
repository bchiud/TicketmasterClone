package com.ticketmaster.booking;

import java.time.Instant;
import java.util.List;

public record BookingResponse(Long id, BookingStatus status, Integer totalCents, Instant expiresAt, Long eventId,
                              Long userId, List<TicketSummary> tickets) {
    public static BookingResponse from(Booking b) {
        return new BookingResponse(b.getId(),
                                   b.getStatus(),
                                   b.getTotalCents(),
                                   b.getExpiresAt(),
                                   b.getEvent().getId(),
                                   b.getUser().getId(),
                                   b.getTickets().stream().map(TicketSummary::from).toList());
    }
}
