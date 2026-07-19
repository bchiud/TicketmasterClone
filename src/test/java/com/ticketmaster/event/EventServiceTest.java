package com.ticketmaster.event;

import com.ticketmaster.seat.Seat;
import com.ticketmaster.seat.SeatRepository;
import com.ticketmaster.ticket.Ticket;
import com.ticketmaster.ticket.TicketRepository;
import com.ticketmaster.ticket.TicketService;
import com.ticketmaster.ticket.TicketStatus;
import com.ticketmaster.venue.Venue;
import com.ticketmaster.venue.VenueRepository;
import com.ticketmaster.venue.exception.VenueHasNoSeatsException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EventService.class, TicketService.class})
class EventServiceTest {

    @Autowired
    private EventService eventService;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private TicketRepository ticketRepository;

    private Venue saveVenue() {
        Venue venue = new Venue();
        venue.setName("Madison Square Garden");
        venue.setCity("New York");
        venue.setAddress("4 Pennsylvania Plaza");
        return venueRepository.save(venue);
    }

    private Venue saveVenueWithSeats(int count) {
        Venue venue = saveVenue();
        for (int i = 1; i <= count; i++) {
            Seat seat = new Seat();
            seat.setVenue(venue);
            seat.setSection("GA");
            seat.setRowLabel("A");
            seat.setSeatNumber(String.valueOf(i));
            seatRepository.save(seat);
        }
        return venue;
    }

    private EventCreateRequest request(Long venueId, ZonedDateTime onSaleAt, int priceCents, boolean requiresQueue) {
        EventCreateRequest request = new EventCreateRequest();
        request.setName("Fan-out Fest");
        request.setPerformer("The Openers");
        request.setVenueId(venueId);
        request.setStartsAt(ZonedDateTime.now()
                                         .plusDays(30));
        request.setOnSaleAt(onSaleAt);
        request.setPriceCents(priceCents);
        request.setRequiresQueue(requiresQueue);
        return request;
    }

    private Event saveEvent(ZonedDateTime onSaleAt) {
        Venue venue = saveVenue();
        Event event = new Event();
        event.setName("Test Concert");
        event.setVenue(venue);
        event.setStartsAt(ZonedDateTime.now()
                                       .plusDays(30));
        event.setOnSaleAt(onSaleAt);
        return eventRepository.save(event);
    }

    @Test
    void activatesAScheduledEventWhosOnSaleTimeHasPassed() {
        Event event = saveEvent(ZonedDateTime.now()
                                             .minusMinutes(1));

        eventService.activateOnSaleEvents();

        assertThat(eventRepository.findById(event.getId()))
                .isPresent()
                .get()
                .extracting(Event::getStatus)
                .isEqualTo(EventStatus.ON_SALE);
    }

    @Test
    void doesNotActivateAnEventWhosOnSaleTimeHasNotYetArrived() {
        Event event = saveEvent(ZonedDateTime.now()
                                             .plusDays(1));

        eventService.activateOnSaleEvents();

        assertThat(eventRepository.findById(event.getId()))
                .isPresent()
                .get()
                .extracting(Event::getStatus)
                .isEqualTo(EventStatus.SCHEDULED);
    }

    @Test
    void doesNotReactivateAnEventThatIsAlreadyPastScheduled() {
        Event event = saveEvent(ZonedDateTime.now()
                                             .minusMinutes(1));
        event.setStatus(EventStatus.SOLD_OUT);
        eventRepository.save(event);

        eventService.activateOnSaleEvents();

        assertThat(eventRepository.findById(event.getId()))
                .isPresent()
                .get()
                .extracting(Event::getStatus)
                .isEqualTo(EventStatus.SOLD_OUT);
    }

    @Test
    void createEventPersistsTheEventWithItsRequestFields() {
        Venue venue = saveVenueWithSeats(3);

        Event created = eventService.createEvent(
                request(venue.getId(), ZonedDateTime.now().minusDays(1), 8000, true));

        assertThat(eventRepository.findById(created.getId()))
                .isPresent()
                .get()
                .satisfies(e -> {
                    assertThat(e.getName()).isEqualTo("Fan-out Fest");
                    assertThat(e.getPerformer()).isEqualTo("The Openers");
                    assertThat(e.getVenue().getId()).isEqualTo(venue.getId());
                    assertThat(e.isRequiresQueue()).isTrue();
                });
    }

    @Test
    void createEventFansOutOneAvailableTicketPerSeatAtTheGivenPrice() {
        Venue venue = saveVenueWithSeats(5);

        Event created = eventService.createEvent(
                request(venue.getId(), ZonedDateTime.now().minusDays(1), 6000, false));

        List<Ticket> tickets = ticketRepository.findByEventId(created.getId());
        assertThat(tickets).hasSize(5)
                           .allSatisfy(t -> {
                               assertThat(t.getStatus()).isEqualTo(TicketStatus.AVAILABLE);
                               assertThat(t.getPriceCents()).isEqualTo(6000);
                               assertThat(t.getEvent().getId()).isEqualTo(created.getId());
                           });
        // one ticket per distinct seat, no seat doubled up
        assertThat(tickets.stream().map(t -> t.getSeat().getId()).distinct()).hasSize(5);
    }

    @Test
    void createEventIsOnSaleImmediatelyWhenOnSaleDateHasPassed() {
        Venue venue = saveVenueWithSeats(1);

        Event created = eventService.createEvent(
                request(venue.getId(), ZonedDateTime.now().minusMinutes(1), 5000, false));

        assertThat(created.getStatus()).isEqualTo(EventStatus.ON_SALE);
    }

    @Test
    void createEventStaysScheduledWhenOnSaleDateIsInTheFuture() {
        Venue venue = saveVenueWithSeats(1);

        Event created = eventService.createEvent(
                request(venue.getId(), ZonedDateTime.now().plusDays(1), 5000, false));

        assertThat(created.getStatus()).isEqualTo(EventStatus.SCHEDULED);
    }

    @Test
    void createEventRejectsAVenueWithNoSeats() {
        Venue venue = saveVenue(); // no seats

        assertThatThrownBy(() -> eventService.createEvent(
                request(venue.getId(), ZonedDateTime.now().minusDays(1), 5000, false)))
                .isInstanceOf(VenueHasNoSeatsException.class);
    }

    @Test
    void createEventRejectsAnUnknownVenue() {
        assertThatThrownBy(() -> eventService.createEvent(
                request(999_999_999L, ZonedDateTime.now().minusDays(1), 5000, false)))
                .isInstanceOf(NoSuchElementException.class);
    }

    // --- markSoldOutIfLastTicketBooked: sold out iff nothing sellable (AVAILABLE/HELD) remains ---

    private Event saveOnSaleEventWithTickets(TicketStatus... statuses) {
        Venue venue = saveVenue();
        Event event = new Event();
        event.setName("Sold Out Test");
        event.setVenue(venue);
        event.setStartsAt(ZonedDateTime.now().plusDays(30));
        event.setStatus(EventStatus.ON_SALE);
        event = eventRepository.save(event);
        for (int i = 0; i < statuses.length; i++) {
            Seat seat = new Seat();
            seat.setVenue(venue);
            seat.setSection("GA");
            seat.setRowLabel("A");
            seat.setSeatNumber(String.valueOf(i));
            seatRepository.save(seat);
            Ticket ticket = new Ticket();
            ticket.setEvent(event);
            ticket.setSeat(seat);
            ticket.setPriceCents(5000);
            ticket.setStatus(statuses[i]);
            ticketRepository.save(ticket);
        }
        return event;
    }

    private EventStatus reloadStatus(Event event) {
        return eventRepository.findById(event.getId()).orElseThrow().getStatus();
    }

    @Test
    void marksSoldOutWhenEveryTicketIsBooked() {
        Event event = saveOnSaleEventWithTickets(TicketStatus.BOOKED, TicketStatus.BOOKED);

        eventService.markSoldOutIfLastTicketBooked(event);

        assertThat(reloadStatus(event)).isEqualTo(EventStatus.SOLD_OUT);
    }

    // the CANCELLED-safe behavior: a voided ticket must not keep the event out of SOLD_OUT
    @Test
    void marksSoldOutWhenRemainingNonBookedTicketsAreCancelled() {
        Event event = saveOnSaleEventWithTickets(TicketStatus.BOOKED, TicketStatus.CANCELLED);

        eventService.markSoldOutIfLastTicketBooked(event);

        assertThat(reloadStatus(event)).isEqualTo(EventStatus.SOLD_OUT);
    }

    @Test
    void doesNotMarkSoldOutWhileATicketIsStillAvailable() {
        Event event = saveOnSaleEventWithTickets(TicketStatus.BOOKED, TicketStatus.AVAILABLE);

        eventService.markSoldOutIfLastTicketBooked(event);

        assertThat(reloadStatus(event)).isEqualTo(EventStatus.ON_SALE);
    }

    @Test
    void doesNotMarkSoldOutWhileATicketIsHeld() {
        Event event = saveOnSaleEventWithTickets(TicketStatus.BOOKED, TicketStatus.HELD);

        eventService.markSoldOutIfLastTicketBooked(event);

        assertThat(reloadStatus(event)).isEqualTo(EventStatus.ON_SALE);
    }
}
