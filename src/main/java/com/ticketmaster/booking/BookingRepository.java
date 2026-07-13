package com.ticketmaster.booking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    List<Booking> findByUserId(Long userId);

    List<Booking> findByStatus(BookingStatus bookingStatus);

    List<Booking> findByStatusAndExpiresAtBefore(BookingStatus bookingStatus, Instant cutoff);

    List<Booking> findByUserIdAndEventIdAndStatusIn(Long userId, Long
            eventId, List<BookingStatus> statuses);
}
