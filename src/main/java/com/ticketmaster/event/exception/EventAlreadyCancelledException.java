package com.ticketmaster.event.exception;

public class EventAlreadyCancelledException extends RuntimeException {
    public EventAlreadyCancelledException(String message) {
        super(message);
    }
}
