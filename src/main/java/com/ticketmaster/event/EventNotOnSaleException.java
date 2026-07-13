package com.ticketmaster.event;

public class EventNotOnSaleException extends RuntimeException {
    public EventNotOnSaleException() {
        super();
    }

    public EventNotOnSaleException(String message) {
        super(message);
    }

    public EventNotOnSaleException(String message, Throwable cause) {
        super(message, cause);
    }
}
