package com.ticketmaster.payment;

import com.ticketmaster.booking.*;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class PaymentService {
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private BookingService bookingService;
    @Autowired
    private PaymentRepository paymentRepository;


    @Transactional
    public Booking pay(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                                           .orElseThrow(
                                                   () -> new NoSuchElementException("Booking not found: " + bookingId));

        Payment payment = new Payment();
        payment.setBooking(booking);
        payment.setProviderRef("fake-" + UUID.randomUUID());
        payment.setAmountCents(booking.getTotalCents());
        payment.setStatus(PaymentStatus.SUCCEEDED);
        paymentRepository.save(payment);

        booking = bookingService.confirm(bookingId);
        return booking;
    }

    @Transactional
    public Booking refund(Long bookingId) {

        Booking booking = bookingRepository.findById(bookingId)
                                           .orElseThrow(
                                                   () -> new NoSuchElementException("Booking not found: " + bookingId));
        if (!booking.getStatus()
                    .equals(BookingStatus.CONFIRMED))
            throw new InvalidBookingState("Booking not confirmed: " + bookingId);

        List<Payment> payments = paymentRepository.findByBookingId(bookingId);
        for (Payment payment : payments) {
            if (payment.getStatus()
                       .equals(PaymentStatus.SUCCEEDED)) {
                Payment refund = new Payment();
                refund.setBooking(booking);
                refund.setProviderRef("fake-" + UUID.randomUUID());
                refund.setAmountCents(booking.getTotalCents());
                refund.setStatus(PaymentStatus.REFUNDED);
                paymentRepository.save(refund);
            }
        }

        booking = bookingService.cancel(bookingId);
        return booking;


    }
}
