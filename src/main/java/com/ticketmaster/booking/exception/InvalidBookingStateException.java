package com.ticketmaster.booking.exception;

public class InvalidBookingStateException extends RuntimeException {
    public InvalidBookingStateException() {
        super();
    }

    public InvalidBookingStateException(String message) {
        super(message);
    }

    public InvalidBookingStateException(String message, Throwable cause) {
        super(message, cause);
    }
}
