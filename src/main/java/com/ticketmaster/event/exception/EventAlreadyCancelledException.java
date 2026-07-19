package com.ticketmaster.event.exception;

public class EventAlreadyCancelledException extends RuntimeException {
    public EventAlreadyCancelledException() {
    }

    public EventAlreadyCancelledException(String message) {
        super(message);
    }

    public EventAlreadyCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
