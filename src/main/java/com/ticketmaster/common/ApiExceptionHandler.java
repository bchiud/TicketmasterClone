package com.ticketmaster.common;

import com.ticketmaster.booking.exception.InvalidBookingStateException;
import com.ticketmaster.event.exception.EventAlreadyCancelledException;
import com.ticketmaster.event.exception.EventNotOnSaleException;
import com.ticketmaster.queue.exception.QueueAccessRequiredException;
import com.ticketmaster.queue.exception.RateLimitException;
import com.ticketmaster.ticket.exception.TicketLimitedExceededException;
import com.ticketmaster.ticket.exception.TicketUnavailableException;
import com.ticketmaster.venue.exception.VenueHasNoSeatsException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<?> handleConcurrencyFailureException(ConcurrencyFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body("Resource was modified concurrently, please retry");
    }

    @ExceptionHandler(EventAlreadyCancelledException.class)
    public ResponseEntity<?> handleEventAlreadyCancelledException(EventAlreadyCancelledException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    @ExceptionHandler(EventNotOnSaleException.class)
    public ResponseEntity<?> handleEventNotOnSaleException(EventNotOnSaleException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    @ExceptionHandler(InvalidBookingStateException.class)
    public ResponseEntity<String> handleInvalidBookingState(InvalidBookingStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    // request body validation exception
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult()
                           .getFieldErrors()  // List<FieldError> of constraint violations
                           .stream()
                           .map(err -> err.getField() + " " + err.getDefaultMessage())
                           .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(message);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> handleNotFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(QueueAccessRequiredException.class)
    public ResponseEntity<String> handleQueueAccessRequired(QueueAccessRequiredException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ex.getMessage());
    }

    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<String> handleRateLimitException(RateLimitException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ex.getMessage());
    }

    @ExceptionHandler(TicketLimitedExceededException.class)
    public ResponseEntity<String> handleTicketLimitedExceeded(TicketLimitedExceededException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    @ExceptionHandler(TicketUnavailableException.class)
    public ResponseEntity<String> handleTicketUnavailable(TicketUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    @ExceptionHandler(VenueHasNoSeatsException.class)
    public ResponseEntity<String> handleVenueHasNoSeatsException(VenueHasNoSeatsException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }
}