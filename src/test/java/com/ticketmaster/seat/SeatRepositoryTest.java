package com.ticketmaster.seat;

import com.ticketmaster.venue.Venue;
import com.ticketmaster.venue.VenueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SeatRepositoryTest {

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private VenueRepository venueRepository;

    private Venue saveVenue() {
        Venue venue = new Venue();
        venue.setName("Chase Center");
        venue.setCity("San Francisco");
        venue.setAddress("1 Warriors Way");
        return venueRepository.save(venue);
    }

    @Test
    void savesAndFindsASeat() {
        Venue venue = saveVenue();

        Seat seat = new Seat();
        seat.setVenue(venue);
        seat.setSection("A");
        seat.setRowLabel("1");
        seat.setSeatNumber("12");

        Seat saved = seatRepository.save(seat);

        assertThat(saved.getId()).isNotNull();
        assertThat(seatRepository.findById(saved.getId())).isPresent();
    }

    @Test
    void findsSeatsByVenueId() {
        Venue venue = saveVenue();

        Seat seat = new Seat();
        seat.setVenue(venue);
        seat.setSection("B");
        seat.setRowLabel("2");
        seat.setSeatNumber("5");
        seatRepository.save(seat);

        List<Seat> results = seatRepository.findByVenueId(venue.getId());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getSection()).isEqualTo("B");
    }
}
