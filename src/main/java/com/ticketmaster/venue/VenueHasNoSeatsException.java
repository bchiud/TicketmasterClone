package com.ticketmaster.venue;

public class VenueHasNoSeatsException extends RuntimeException{
    public VenueHasNoSeatsException() {
        super();
    }

    public VenueHasNoSeatsException(String message) {
        super(message);
    }

    public VenueHasNoSeatsException(String message, Throwable cause) {
        super(message, cause);
    }
}
