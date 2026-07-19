package com.ticketmaster.ticket;

import com.ticketmaster.booking.Booking;
import com.ticketmaster.event.Event;
import com.ticketmaster.seat.Seat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TicketController.class)
class TicketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TicketRepository ticketRepository;

    // TicketResponse.from() dereferences event and seat always, and booking only when present
    // (AVAILABLE tickets have no booking).
    private static Ticket ticket(Long id, TicketStatus status, Long bookingId) {
        Event event = new Event();
        event.setId(3L);
        Seat seat = new Seat();
        seat.setId(50L);
        Ticket t = new Ticket();
        t.setId(id);
        t.setEvent(event);
        t.setSeat(seat);
        t.setStatus(status);
        t.setPriceCents(5000);
        if (bookingId != null) {
            Booking b = new Booking();
            b.setId(bookingId);
            t.setBooking(b);
        }
        return t;
    }

    @Test
    void getHeldTicketExposesBookingId() throws Exception {
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket(1L, TicketStatus.HELD, 9L)));

        mockMvc.perform(get("/tickets/1"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.priceCents").value(5000))
               .andExpect(jsonPath("$.eventId").value(3))
               .andExpect(jsonPath("$.seatId").value(50))
               .andExpect(jsonPath("$.bookingId").value(9));
    }

    // An AVAILABLE ticket has no booking; the DTO's bookingId must be null, not an NPE.
    @Test
    void getAvailableTicketReturnsNullBookingId() throws Exception {
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket(1L, TicketStatus.AVAILABLE, null)));

        mockMvc.perform(get("/tickets/1"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.bookingId").value(nullValue()));
    }

    @Test
    void getTicketReturns404WhenNotFound() throws Exception {
        when(ticketRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/tickets/99"))
               .andExpect(status().isNotFound());
    }

    @Test
    void getTicketsForEventReturnsAllTicketsWhenNoStatusGiven() throws Exception {
        when(ticketRepository.findByEventId(3L))
                .thenReturn(List.of(ticket(1L, TicketStatus.AVAILABLE, null)));

        mockMvc.perform(get("/events/3/tickets"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void getTicketsForEventFiltersByStatusWhenGiven() throws Exception {
        when(ticketRepository.findByEventIdAndStatus(3L, TicketStatus.AVAILABLE))
                .thenReturn(List.of(ticket(2L, TicketStatus.AVAILABLE, null)));

        mockMvc.perform(get("/events/3/tickets").param("status", "AVAILABLE"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].id").value(2));
    }
}
