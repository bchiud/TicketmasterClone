package com.ticketmaster.ticket.exception;

public class TicketLimitedExceededException extends RuntimeException {
    public TicketLimitedExceededException(String message) {
        super(message);
    }
}
