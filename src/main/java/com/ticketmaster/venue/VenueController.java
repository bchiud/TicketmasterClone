package com.ticketmaster.venue;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/venues")
public class VenueController {
    private final VenueRepository venueRepository;

    public VenueController(VenueRepository venueRepository) {
        this.venueRepository = venueRepository;
    }

    @PostMapping("")
    @ResponseStatus(HttpStatus.CREATED)
    public Venue createVenue(@Valid @RequestBody VenueCreateRequest venueCreateRequest) {
        Venue venue = new Venue();
        venue.setName(venueCreateRequest.getName());
        venue.setAddress(venueCreateRequest.getAddress());
        venue.setCity(venueCreateRequest.getCity());
        return venueRepository.save(venue);
    }

    @GetMapping("")
    public List<Venue> getAllVenues() {
        return venueRepository.findAll();
    }

    @GetMapping("/{id}")
    public Venue getVenueById(@PathVariable Long id) {
        return venueRepository.findById(id)
                              .orElseThrow(() -> new NoSuchElementException("Venue not found: " + id));
    }


}
