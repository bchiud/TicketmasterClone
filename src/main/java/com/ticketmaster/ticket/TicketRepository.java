package com.ticketmaster.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    List<Ticket> findByEventId(Long eventId);

    List<Ticket> findByEventIdAndStatus(Long eventId, TicketStatus status);
}