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
    public VenueResponse createVenue(@Valid @RequestBody VenueCreateRequest venueCreateRequest) {
        Venue venue = new Venue();
        venue.setName(venueCreateRequest.getName());
        venue.setAddress(venueCreateRequest.getAddress());
        venue.setCity(venueCreateRequest.getCity());
        return VenueResponse.from(venueRepository.save(venue));
    }

    @GetMapping("")
    public List<VenueResponse> getAllVenues() {
        return venueRepository.findAll().stream().map(VenueResponse::from).toList();
    }

    @GetMapping("/{id}")
    public VenueResponse getVenueById(@PathVariable Long id) {
        return VenueResponse.from(venueRepository.findById(id)
                                                 .orElseThrow(() -> new NoSuchElementException(
                                                         "Venue not found: " + id)));
    }


}
