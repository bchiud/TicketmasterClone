package com.ticketmaster.ticket;

import com.ticketmaster.booking.Booking;
import com.ticketmaster.event.Event;
import com.ticketmaster.seat.Seat;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
// @formatter:off
@Table(
        name = "tickets",
        indexes = {
                @Index(name = "idx_tickets_event_status", columnList = "event_id, status")
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

    @ManyToOne
    private Booking booking;
}




