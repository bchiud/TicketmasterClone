package com.ticketmaster.payment;

import com.ticketmaster.booking.Booking;
import com.ticketmaster.booking.BookingRepository;
import com.ticketmaster.booking.BookingStatus;
import com.ticketmaster.event.Event;
import com.ticketmaster.event.EventRepository;
import com.ticketmaster.user.User;
import com.ticketmaster.user.UserRepository;
import com.ticketmaster.venue.Venue;
import com.ticketmaster.venue.VenueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaymentRepositoryTest {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private EventRepository eventRepository;

    private Booking saveBooking(String idempotencyKey) {
        User user = new User();
        user.setEmail(idempotencyKey + "@example.com");
        userRepository.save(user);

        Venue venue = new Venue();
        venue.setName("Madison Square Garden");
        venue.setCity("New York");
        venue.setAddress("4 Pennsylvania Plaza");
        venueRepository.save(venue);

        Event event = new Event();
        event.setName("Test Concert");
        event.setVenue(venue);
        event.setStartsAt(ZonedDateTime.now()
                                       .plusDays(30));
        eventRepository.save(event);

        Booking booking = new Booking();
        booking.setUser(user);
        booking.setEvent(event);
        booking.setStatus(BookingStatus.PENDING);
        booking.setTotalCents(150);
        booking.setIdempotencyKey(idempotencyKey);
        booking.setExpiresAt(Instant.now()
                                     .plusSeconds(600));
        return bookingRepository.save(booking);
    }

    private Payment newPayment(Booking booking, PaymentStatus status) {
        Payment payment = new Payment();
        payment.setBooking(booking);
        payment.setProviderRef("fake-ref");
        payment.setAmountCents(150);
        payment.setStatus(status);
        return payment;
    }

    @Test
    void savesAndFindsAPayment() {
        Booking booking = saveBooking("pay-repo-1");

        Payment saved = paymentRepository.save(newPayment(booking, PaymentStatus.SUCCEEDED));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(paymentRepository.findById(saved.getId()))
                .isPresent()
                .get()
                .extracting(Payment::getStatus)
                .isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void findsAllPaymentsForABooking() {
        Booking booking = saveBooking("pay-repo-2");
        paymentRepository.save(newPayment(booking, PaymentStatus.FAILED));
        paymentRepository.save(newPayment(booking, PaymentStatus.SUCCEEDED));

        List<Payment> payments = paymentRepository.findByBookingId(booking.getId());

        assertThat(payments).hasSize(2)
                             .extracting(Payment::getStatus)
                             .containsExactlyInAnyOrder(PaymentStatus.FAILED, PaymentStatus.SUCCEEDED);
    }

    @Test
    void findByBookingIdReturnsEmptyListWhenNoneExist() {
        Booking booking = saveBooking("pay-repo-3");

        List<Payment> payments = paymentRepository.findByBookingId(booking.getId());

        assertThat(payments).isEmpty();
    }
}
