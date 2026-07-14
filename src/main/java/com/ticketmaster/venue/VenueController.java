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
    public Venue createVenue(@Valid @RequestBody CreateVenueRequest createVenueRequest) {
        Venue venue = new Venue();
        venue.setName(createVenueRequest.getName());
        venue.setAddress(createVenueRequest.getAddress());
        venue.setCity(createVenueRequest.getCity());
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
