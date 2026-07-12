package com.ticketmaster.common;

import com.ticketmaster.booking.InvalidBookingState;
import com.ticketmaster.ticket.TicketUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> handleNotFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                             .body(ex.getMessage());
    }

    @ExceptionHandler(TicketUnavailableException.class)
    public ResponseEntity<String> handleTicketUnavailable(TicketUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                             .body(ex.getMessage());
    }

    @ExceptionHandler(InvalidBookingState.class)
    public ResponseEntity<String> handleInvalidBookingState(InvalidBookingState ex){
        return ResponseEntity.status(HttpStatus.CONFLICT)
                             .body(ex.getMessage());
    }
}