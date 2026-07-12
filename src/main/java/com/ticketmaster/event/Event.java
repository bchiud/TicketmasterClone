package com.ticketmaster.event;

import com.ticketmaster.venue.Venue;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.ZonedDateTime;

@Entity
@Table(name = "events")
@Getter
@Setter
@NoArgsConstructor
public class Event {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String performer;

    @ManyToOne
    private Venue venue;

    private ZonedDateTime startsAt;

    private ZonedDateTime onSaleAt;

    @Enumerated(EnumType.STRING)
    private EventStatus status = EventStatus.SCHEDULED;
}
