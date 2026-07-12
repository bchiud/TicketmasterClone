package com.ticketmaster.venue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class VenueRepositoryTest {

    @Autowired
    private VenueRepository venueRepository;

    @Test
    void savesAndFindsAVenue() {
        Venue venue = new Venue();
        venue.setName("Chase Center");
        venue.setCity("San Francisco");
        venue.setAddress("1 Warriors Way");

        Venue saved = venueRepository.save(venue);

        assertThat(saved.getId()).isNotNull();
        assertThat(venueRepository.findById(saved.getId()))
                .isPresent()
                .get()
                .extracting(Venue::getCity)
                .isEqualTo("San Francisco");
    }
}
