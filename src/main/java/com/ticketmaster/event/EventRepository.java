package com.ticketmaster.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.ZonedDateTime;
import java.util.List;

// <Entity, Entity Id Type>
public interface EventRepository extends JpaRepository<Event, Long>, JpaSpecificationExecutor<Event> {
    List<Event> findByVenueId(Long venueId);

    List<Event> findByStatus(EventStatus status);

    List<Event> findByNameContainingIgnoreCase(String name);

    List<Event> findByNameContainingIgnoreCaseAndStatus(String name, EventStatus status);

    List<Event> findByStatusAndOnSaleAtBefore(EventStatus status,
  ZonedDateTime cutoff);
}
