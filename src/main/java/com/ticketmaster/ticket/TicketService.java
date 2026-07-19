package com.ticketmaster.ticket;

import com.ticketmaster.event.Event;
import com.ticketmaster.seat.Seat;
import com.ticketmaster.seat.SeatRepository;
import com.ticketmaster.venue.exception.VenueHasNoSeatsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TicketService {
    @Autowired
    private SeatRepository seatRepository;
    @Autowired
    private TicketRepository ticketRepository;

    public void cancelTicketsbyEventId(Long eventId) {
        List<Ticket> tickets = ticketRepository.findByEventId(eventId);
        for (Ticket ticket : tickets)
            ticket.setStatus(TicketStatus.CANCELLED);
        ticketRepository.saveAll(tickets);
    }

    public List<Ticket> generateForEvent(Event event, int priceCents) {
        List<Seat> seats = seatRepository.findByVenueId(event.getVenue()
                                                             .getId());
        if (seats == null || seats.isEmpty())
            throw new VenueHasNoSeatsException("Venue has no seats: " + event.getVenue()
                                                                             .getId());

        List<Ticket> tickets = new ArrayList<>();
        for (Seat seat : seats) {
            Ticket ticket = new Ticket();
            ticket.setEvent(event);
            ticket.setSeat(seat);
            ticket.setPriceCents(priceCents);
            tickets.add(ticket);
        }
        ticketRepository.saveAll(tickets);
        return tickets;
    }
}
