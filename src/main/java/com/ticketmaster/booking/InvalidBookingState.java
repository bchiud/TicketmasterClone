package com.ticketmaster.booking;

public class InvalidBookingState extends RuntimeException {
    public InvalidBookingState() {
        super();
    }

    public InvalidBookingState(String message) {
        super(message);
    }

    public InvalidBookingState(String message, Throwable cause) {
        super(message, cause);
    }
}
