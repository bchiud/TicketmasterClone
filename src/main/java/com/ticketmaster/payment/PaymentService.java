package com.ticketmaster.payment;

import com.ticketmaster.booking.Booking;
import com.ticketmaster.booking.BookingRepository;
import com.ticketmaster.booking.BookingService;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
}
