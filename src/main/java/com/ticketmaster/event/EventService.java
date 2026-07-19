package com.ticketmaster.event;

import com.ticketmaster.event.exception.EventNotOnSaleException;
import com.ticketmaster.ticket.TicketRepository;
import com.ticketmaster.ticket.TicketService;
import com.ticketmaster.ticket.TicketStatus;
import com.ticketmaster.venue.Venue;
import com.ticketmaster.venue.VenueRepository;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@Service
@Slf4j
public class EventService {
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private VenueRepository venueRepository;

    @Scheduled(fixedDelayString = "${event.on-sale-sweep-interval-ms:30000}")
    public void activateOnSaleEvents() {
        List<Event> events = eventRepository.findByStatusAndOnSaleAtBefore(EventStatus.SCHEDULED, ZonedDateTime.now());
        for (Event event : events) {
            try {
                event.setStatus(EventStatus.ON_SALE);
                eventRepository.save(event);
            } catch (Exception ex) {
                log.error("Failed to transition event to on-sale: " + event.getId(), ex);
            }
        }
    }

    @Transactional
    public Event createEvent(EventCreateRequest eventCreateRequest) {
        Venue venue = venueRepository.findById(eventCreateRequest.getVenueId())
                                     .orElseThrow(() -> new NoSuchElementException(
                                             "Venue not found: " + eventCreateRequest.getVenueId()));

        Event event = new Event();
        event.setName(eventCreateRequest.getName());
        event.setPerformer(eventCreateRequest.getPerformer());
        event.setVenue(venue);
        event.setStartsAt(eventCreateRequest.getStartsAt());
        event.setOnSaleAt(eventCreateRequest.getOnSaleAt());
        if (eventCreateRequest.getOnSaleAt()
                              .isBefore(ZonedDateTime.now())) event.setStatus(EventStatus.ON_SALE);
        if (eventCreateRequest.isRequiresQueue()) event.setRequiresQueue(true);
        event = eventRepository.save(event);

        ticketService.generateForEvent(event, eventCreateRequest.getPriceCents());

        return event;
    }

    public Event getEventIfOnSale(Long eventId) {
        Event event = eventRepository.findById(eventId)
                                     .orElseThrow(() -> new NoSuchElementException("Event not found: " + eventId));
        if (event.getStatus() != EventStatus.ON_SALE) throw new EventNotOnSaleException("Event currently not on sale");
        return event;
    }

    public void markSoldOutIfLastTicketBooked(Event event) {
        if (ticketRepository.findByEventId(event.getId())
                            .stream()
                            .filter(t -> t.getStatus() != TicketStatus.BOOKED)
                            .count() == 0) {
            event.setStatus(EventStatus.SOLD_OUT);
            eventRepository.save(event);
        }
    }
}
