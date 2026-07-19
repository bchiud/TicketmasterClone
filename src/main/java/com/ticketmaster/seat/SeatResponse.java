package com.ticketmaster.seat;

public record SeatResponse(Long id, Long venueId, String section, String rowLabel, String seatNumber) {
    public static SeatResponse from(Seat s) {
        return new SeatResponse(s.getId(), s.getVenue().getId(), s.getSection(), s.getRowLabel(), s.getSeatNumber());
    }
}
