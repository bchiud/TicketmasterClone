package com.ticketmaster.payment;

import com.ticketmaster.booking.BookingResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
public class PaymentController {
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;

    public PaymentController(PaymentRepository paymentRepository, PaymentService paymentService) {
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
    }

    @GetMapping("/payments/{id}")
    public PaymentResponse getPayment(@PathVariable Long id) {
        return PaymentResponse.from(paymentRepository.findById(id)
                                                     .orElseThrow(() -> new NoSuchElementException(
                                                             "Payment not found: " + id)));
    }

    @GetMapping("/bookings/{id}/payments")
    public List<PaymentResponse> getPaymentsByBookingId(@PathVariable Long id) {
        return paymentRepository.findByBookingId(id).stream().map(PaymentResponse::from).toList();
    }

    @PostMapping("/bookings/{id}/refund")
    public BookingResponse refundBooking(@PathVariable Long id) {
        return BookingResponse.from(paymentService.refund(id));
    }
}
