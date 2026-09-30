package com.ticketmaster.venue.exception;

public class VenueHasNoSeatsException extends RuntimeException {
    public VenueHasNoSeatsException(String message) {
        super(message);
    }
}
