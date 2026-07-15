package com.ticketmaster.event;

import com.ticketmaster.venue.Venue;
import com.ticketmaster.venue.VenueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EventSpecificationsTest {

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private VenueRepository venueRepository;

    // unique per run: every event's name carries this token, so scoping any query by name=TOKEN
    // isolates this test's rows from anything else committed to the shared test database.
    private String token;
    private ZonedDateTime june, july, august;

    @BeforeEach
    void seed() {
        token = "spec-" + UUID.randomUUID();
        june = ZonedDateTime.parse("2026-06-01T20:00:00Z");
        july = ZonedDateTime.parse("2026-07-01T20:00:00Z");
        august = ZonedDateTime.parse("2026-08-01T20:00:00Z");

        // A: NY / Miles / June / ON_SALE
        event("Jazz Night " + token, "Miles Davis", "New York", june, EventStatus.ON_SALE);
        // B: Boston / Foo / August / ON_SALE
        event("Rock Fest " + token, "Foo Fighters", "Boston", august, EventStatus.ON_SALE);
        // C: NY / Miles / July / SCHEDULED
        event("Jazz Brunch " + token, "Miles Davis", "New York", july, EventStatus.SCHEDULED);
    }

    private void event(String name, String performer, String city, ZonedDateTime startsAt, EventStatus status) {
        Venue venue = new Venue();
        venue.setName("Venue " + UUID.randomUUID());
        venue.setCity(city);
        venueRepository.save(venue);

        Event event = new Event();
        event.setName(name);
        event.setPerformer(performer);
        event.setVenue(venue);
        event.setStartsAt(startsAt);
        event.setStatus(status);
        eventRepository.save(event);
    }

    // scope every query to this run's events, then AND in the filter under test
    private List<Event> find(EventStatus status, String city, String performer, ZonedDateTime from, ZonedDateTime to) {
        return eventRepository.findAll(EventSpecifications.matching(token, status, city, performer, from, to));
    }

    private List<String> names(List<Event> events) {
        return events.stream().map(Event::getName).toList();
    }

    @Test
    void noFiltersReturnsAllScopedEvents() {
        assertThat(find(null, null, null, null, null)).hasSize(3);
    }

    @Test
    void filtersByNameSubstringCaseInsensitive() {
        // querying the token in UPPER-CASE still matches the lower-case names, proving the
        // filter is both a substring match (token is mid-name) and case-insensitive.
        assertThat(find(null, null, null, null, null)).hasSize(3); // sanity: token scopes to 3
        List<Event> upper = eventRepository.findAll(
                EventSpecifications.matching(token.toUpperCase(), null, null, null, null, null));
        assertThat(names(upper)).containsExactlyInAnyOrder(
                "Jazz Night " + token, "Rock Fest " + token, "Jazz Brunch " + token);
    }

    @Test
    void filtersByCityViaVenueJoinCaseInsensitive() {
        assertThat(names(find(null, "new york", null, null, null)))
                .containsExactlyInAnyOrder("Jazz Night " + token, "Jazz Brunch " + token);
    }

    @Test
    void filtersByPerformerSubstringCaseInsensitive() {
        assertThat(names(find(null, null, "miles", null, null)))
                .containsExactlyInAnyOrder("Jazz Night " + token, "Jazz Brunch " + token);
    }

    @Test
    void filtersByStatus() {
        assertThat(names(find(EventStatus.ON_SALE, null, null, null, null)))
                .containsExactlyInAnyOrder("Jazz Night " + token, "Rock Fest " + token);
    }

    @Test
    void filtersByStartsAtLowerBoundInclusive() {
        // from = July 1 -> July and August events (>=), not June
        assertThat(names(find(null, null, null, july, null)))
                .containsExactlyInAnyOrder("Jazz Brunch " + token, "Rock Fest " + token);
    }

    @Test
    void filtersByStartsAtUpperBoundInclusive() {
        // to = July 1 -> June and July events (<=), not August
        assertThat(names(find(null, null, null, null, july)))
                .containsExactlyInAnyOrder("Jazz Night " + token, "Jazz Brunch " + token);
    }

    @Test
    void filtersByDateRange() {
        // (June 15 .. July 15] -> only the July event
        assertThat(names(find(null, null, null,
                              ZonedDateTime.parse("2026-06-15T00:00:00Z"),
                              ZonedDateTime.parse("2026-07-15T00:00:00Z"))))
                .containsExactly("Jazz Brunch " + token);
    }

    @Test
    void combinesFiltersWithAnd() {
        // NY + ON_SALE -> only Jazz Night (Jazz Brunch is NY but SCHEDULED)
        assertThat(names(find(EventStatus.ON_SALE, "new york", null, null, null)))
                .containsExactly("Jazz Night " + token);
    }
}
