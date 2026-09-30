package com.ticketmaster.queue.exception;

public class QueueAccessRequiredException extends RuntimeException {
    public QueueAccessRequiredException(String message) {
        super(message);
    }
}
