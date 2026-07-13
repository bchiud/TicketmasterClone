package com.ticketmaster.event;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.ZonedDateTime;
import java.util.List;

// <Entity, Entity Id Type>
public interface EventRepository extends JpaRepository<Event, Long> {
    List<Event> findByVenueId(Long venueId);

    List<Event> findByStatus(EventStatus status);

    List<Event> findByNameContainingIgnoreCase(String name);

    List<Event> findByNameContainingIgnoreCaseAndStatus(String name, EventStatus status);

    List<Event> findByStatusAndOnSaleAtBefore(EventStatus status,
  ZonedDateTime cutoff);
}
