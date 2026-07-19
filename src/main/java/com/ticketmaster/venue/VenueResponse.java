package com.ticketmaster.venue;

public record VenueResponse(Long id, String name, String address, String city) {
    public static VenueResponse from(Venue v) {
        return new VenueResponse(v.getId(), v.getName(), v.getAddress(), v.getCity());
    }
}
