package com.ticketmaster.venue;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class VenueCreateRequest {
    @NotBlank
    private String name;
    @NotBlank
    private String address;
    @NotBlank
    private String city;
}
