package com.ticketmaster.seat;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

@RestController

public class SeatController {
    private final SeatRepository seatRepository;

    public SeatController(SeatRepository seatRepository) {
        this.seatRepository = seatRepository;
    }

    @GetMapping("/seats/{id}")
    public Seat getSeat(@PathVariable Long id) {
        return seatRepository.findById(id)
                             .orElseThrow(() -> new NoSuchElementException("Seat not found: " + id));
    }

    @GetMapping("/venues/{venueId}/seats")
    public List<Seat> getSeats(@PathVariable Long venueId) {
        return seatRepository.findByVenueId(venueId);
    }
}
