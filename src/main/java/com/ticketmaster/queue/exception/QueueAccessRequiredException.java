package com.ticketmaster.queue.exception;

public class QueueAccessRequiredException extends RuntimeException {
    public QueueAccessRequiredException() {
        super();
    }

    public QueueAccessRequiredException(String message) {
        super(message);
    }

    public QueueAccessRequiredException(String message, Throwable cause) {
        super(message, cause);
    }
}
