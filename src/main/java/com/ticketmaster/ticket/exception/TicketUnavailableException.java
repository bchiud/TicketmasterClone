package com.ticketmaster.ticket.exception;

public class TicketUnavailableException extends RuntimeException {
    public TicketUnavailableException(String message) {
        super(message);
    }
}
