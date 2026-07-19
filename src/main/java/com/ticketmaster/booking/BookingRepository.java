package com.ticketmaster.booking;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    // @EntityGraph fetches tickets in the same query
    // without it, mapping runs outside the transaction (OSIV off) and throws LazyInitializationException
    // even with a session open, per-booking fetching would be n + 1
    @EntityGraph(attributePaths = "tickets")
    Optional<Booking> findWithTicketsById(Long id);

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    List<Booking> findByEventIdAndStatusIn(Long eventId, List<BookingStatus> statuses);

    @EntityGraph(attributePaths = "tickets")
    List<Booking> findWithTicketsByUserId(Long userId);

    List<Booking> findByStatus(BookingStatus bookingStatus);

    List<Booking> findByStatusAndExpiresAtBefore(BookingStatus bookingStatus, Instant cutoff);

    List<Booking> findByUserIdAndEventIdAndStatusIn(Long userId, Long eventId, List<BookingStatus> statuses);
}
