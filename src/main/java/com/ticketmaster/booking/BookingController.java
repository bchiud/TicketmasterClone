package com.ticketmaster.booking;

import com.ticketmaster.payment.PaymentService;
import com.ticketmaster.queue.QueueAccessRequiredException;
import com.ticketmaster.queue.QueueService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
public class BookingController {
    private BookingRepository bookingRepository;
    private BookingService bookingService;
    private PaymentService paymentService;
    private QueueService queueService;

    public BookingController(BookingRepository bookingRepository, BookingService bookingService,
                             PaymentService paymentService, QueueService queueService) {
        this.bookingRepository = bookingRepository;
        this.bookingService = bookingService;
        this.paymentService = paymentService;
        this.queueService = queueService;
    }

    @GetMapping("/bookings/{id}")
    public Booking getBookingById(@PathVariable Long id) {
        return bookingRepository.findById(id)
                                .orElseThrow(() -> new NoSuchElementException("Booking not found: " + id));
    }

    @GetMapping("/users/{userId}/bookings")
    public List<Booking> getBookingsByUserId(@PathVariable Long userId) {
        return bookingRepository.findByUserId(userId);
    }

    @PostMapping("/bookings/hold")
    public Booking holdBooking(@Valid @RequestBody BookingHoldRequest bookingHoldRequest) {
        if (!queueService.hasAccess(bookingHoldRequest.getAccessToken()))
            throw new QueueAccessRequiredException("Access Denied");

        return bookingService.hold(bookingHoldRequest.getUserId(), bookingHoldRequest.getEventId(),
                                   bookingHoldRequest.getTicketIds(), bookingHoldRequest.getIdempotencyKey());
    }

    @PostMapping("/bookings/{id}/pay")
    public Booking payBooking(@PathVariable Long id) {
        return paymentService.pay(id);
    }

    @PostMapping("/bookings/{id}/cancel")
    public Booking cancelBooking(@PathVariable Long id) {
        return bookingService.cancel(id);
    }
}
