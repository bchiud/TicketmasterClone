package com.ticketmaster.ticket.exception;

public class TicketLimitedExceededException extends RuntimeException {
    public TicketLimitedExceededException() {
        super();
    }

    public TicketLimitedExceededException(String message) {
        super(message);
    }

    public TicketLimitedExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
