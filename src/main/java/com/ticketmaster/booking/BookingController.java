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

    public BookingController(BookingRepository bookingRepository,
                             BookingService bookingService,
                             PaymentService paymentService) {
        this.bookingRepository = bookingRepository;
        this.bookingService = bookingService;
        this.paymentService = paymentService;
    }

    @GetMapping("/bookings/{id}")
    public BookingResponse getBookingById(@PathVariable Long id) {
        return BookingResponse.from(bookingRepository.findWithTicketsById(id)
                                                     .orElseThrow(() -> new NoSuchElementException(
                                                             "Booking not found: " + id)));
    }

    @GetMapping("/users/{userId}/bookings")
    public List<BookingResponse> getBookingsByUserId(@PathVariable Long userId) {
        return bookingRepository.findWithTicketsByUserId(userId).stream().map(BookingResponse::from).toList();
    }

    @PostMapping("/bookings/hold")
    public BookingResponse holdBooking(@Valid @RequestBody BookingHoldRequest bookingHoldRequest) {
        return BookingResponse.from(bookingService.hold(bookingHoldRequest.getUserId(),
                                                        bookingHoldRequest.getEventId(),
                                                        bookingHoldRequest.getTicketIds(),
                                                        bookingHoldRequest.getIdempotencyKey(),
                                                        bookingHoldRequest.getAccessToken()));
    }

    @PostMapping("/bookings/{id}/pay")
    public BookingResponse payBooking(@PathVariable Long id) {
        return BookingResponse.from(paymentService.pay(id));
    }

    @PostMapping("/bookings/{id}/cancel")
    public BookingResponse cancelBooking(@PathVariable Long id) {
        return BookingResponse.from(bookingService.cancel(id));
    }
}
