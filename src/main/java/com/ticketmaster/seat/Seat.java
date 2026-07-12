package com.ticketmaster.seat;

import com.ticketmaster.venue.Venue;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "seats",
        uniqueConstraints = @UniqueConstraint(columnNames = {"venue_id", "section", "row_label", "seat_number"})
)
@Getter
@Setter
@NoArgsConstructor
public class Seat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private Venue venue;

    private String section;

    private String rowLabel;

    private String seatNumber;


}
