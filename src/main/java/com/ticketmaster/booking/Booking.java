package com.ticketmaster.booking;

import com.ticketmaster.event.Event;
import com.ticketmaster.ticket.Ticket;
import com.ticketmaster.user.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "bookings", uniqueConstraints = @UniqueConstraint(columnNames = {"idempotency_key"}))
@Getter
@Setter
@NoArgsConstructor
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private User user;

    @ManyToOne
    private Event event;

    @OneToMany
    private List<Ticket> tickets;

    @Enumerated(EnumType.STRING)
    private BookingStatus status = BookingStatus.PENDING;

    private Integer totalCents;

    private String idempotencyKey;

    // instant is more idiomatic b/c comparisons here are tz agnostic
    private Instant expiresAt;

    @CreationTimestamp
    private Instant createdAt;
}
