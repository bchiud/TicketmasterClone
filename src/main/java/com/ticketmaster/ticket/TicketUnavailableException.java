package com.ticketmaster.ticket;

public class TicketUnavailableException extends RuntimeException {
    public TicketUnavailableException() {
        super();
    }

    public TicketUnavailableException(String message) {
        super(message);
    }

    public TicketUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
