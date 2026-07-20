package com.ticketmaster.payment;

import com.ticketmaster.booking.Booking;
import com.ticketmaster.booking.BookingRepository;
import com.ticketmaster.booking.BookingService;
import com.ticketmaster.booking.BookingStatus;
import com.ticketmaster.booking.exception.InvalidBookingStateException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        Booking booking = bookingRepository.findWithTicketsById(bookingId)
                                           .orElseThrow(() -> new NoSuchElementException(
                                                   "Booking not found: " + bookingId));

        // idempotent: already paid → return the confirmed booking, don't charge again
        if (booking.getStatus() == BookingStatus.CONFIRMED) return booking;

        if (booking.getStatus() != BookingStatus.PENDING)
            throw new InvalidBookingStateException("Booking not payable: " + booking.getStatus());

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
                                           .orElseThrow(() -> new NoSuchElementException(
                                                   "Booking not found: " + bookingId));
        if (!booking.getStatus().equals(BookingStatus.CONFIRMED))
            throw new InvalidBookingStateException("Booking not confirmed: " + bookingId);

        List<Payment> payments = paymentRepository.findByBookingId(bookingId);
        for (Payment payment : payments) {
            if (payment.getStatus().equals(PaymentStatus.SUCCEEDED)) {
                Payment refund = new Payment();
                refund.setBooking(booking);
                refund.setProviderRef("fake-" + UUID.randomUUID());
                refund.setAmountCents(payment.getAmountCents());
                refund.setStatus(PaymentStatus.REFUNDED);
                paymentRepository.save(refund);
            }
        }

        booking = bookingService.cancel(bookingId);
        return booking;
    }
}
