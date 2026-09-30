package com.ticketmaster.event.exception;

public class EventNotOnSaleException extends RuntimeException {
    public EventNotOnSaleException(String message) {
        super(message);
    }
}
