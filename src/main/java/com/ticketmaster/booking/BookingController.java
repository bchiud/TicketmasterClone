package com.ticketmaster.booking;

import com.ticketmaster.payment.PaymentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
public class BookingController {
    private BookingRepository bookingRepository;
    private BookingService bookingService;
    private PaymentService paymentService;

    public BookingController(BookingRepository bookingRepository, BookingService bookingService,
                             PaymentService paymentService) {
        this.bookingRepository = bookingRepository;
        this.bookingService = bookingService;
        this.paymentService = paymentService;
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
        return bookingService.hold(bookingHoldRequest.getUserId(), bookingHoldRequest.getEventId(),
                                   bookingHoldRequest.getTicketIds(), bookingHoldRequest.getIdempotencyKey(),
                                   bookingHoldRequest.getAccessToken());
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
