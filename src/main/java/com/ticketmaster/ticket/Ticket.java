package com.ticketmaster.ticket;

import com.ticketmaster.booking.Booking;
import com.ticketmaster.event.Event;
import com.ticketmaster.seat.Seat;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.ZonedDateTime;

@Entity
// @formatter:off
@Table(
        name = "tickets",
        indexes = {
                @Index(name = "idx_tickets_event_status", columnList = "event_id, status"),
                @Index(name = "idx_tickets_hold_expiry", columnList = "status, hold_expires_at")
        },
        uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "seat_id"})
)
// @formatter:on
@Getter
@Setter
@NoArgsConstructor
public class Ticket {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private Event event;

    @ManyToOne
    private Seat seat;

    private int priceCents;

    @Enumerated(EnumType.STRING)
    private TicketStatus status = TicketStatus.AVAILABLE;

    private Long heldBy;

    private ZonedDateTime holdExpiresAt;

    @ManyToOne
    private Booking booking;
}




