package com.ticketmaster.booking;

import com.ticketmaster.event.Event;
import com.ticketmaster.event.EventRepository;
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
    private EventRepository eventRepository;
    private PaymentService paymentService;
    private QueueService queueService;

    public BookingController(BookingRepository bookingRepository, BookingService bookingService,
                             EventRepository eventRepository, PaymentService paymentService,
                             QueueService queueService) {
        this.bookingRepository = bookingRepository;
        this.bookingService = bookingService;
        this.eventRepository = eventRepository;
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
        if (eventRepository.findById(bookingHoldRequest.getEventId())
                           .map(Event::isRequiresQueue)
                           .orElse(false)) {
            String token = bookingHoldRequest.getAccessToken();
            if (token == null || token.isBlank() || !queueService.hasAccess(token))
                throw new QueueAccessRequiredException("Access Denied");
        }

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
