package com.ticketmaster.ticket;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    List<Ticket> findByEventId(Long eventId);

    List<Ticket> findByEventIdAndStatus(Long eventId, TicketStatus status);

    // pessimistic write: postgres "FOR NO KEY UPDATE NOWAIT"
    // (java) lock.timeout = 0 -> don't wait for the lock
    // row lock held until commit; second hold on the same ticket fails immediately instead of waiting
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    List<Ticket> findByIdIn(List<Long> ids);

    long countByEventIdAndStatusIn(Long eventId, List<TicketStatus> statuses);
}