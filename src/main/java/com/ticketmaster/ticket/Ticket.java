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
@Table(name = "tickets", uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "seat_id"}))
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




