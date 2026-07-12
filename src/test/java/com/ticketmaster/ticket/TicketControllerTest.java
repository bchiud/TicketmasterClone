package com.ticketmaster.ticket;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

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

    @Test
    void getTicketReturnsTicketWhenFound() throws Exception {
        Ticket ticket = new Ticket();
        ticket.setId(1L);
        ticket.setPriceCents(5000);
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));

        mockMvc.perform(get("/tickets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceCents").value(5000));
    }

    @Test
    void getTicketsForEventReturnsAllTicketsWhenNoStatusGiven() throws Exception {
        Ticket ticket = new Ticket();
        ticket.setId(1L);
        when(ticketRepository.findByEventId(3L)).thenReturn(List.of(ticket));

        mockMvc.perform(get("/events/3/tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1));
    }

    @Test
    void getTicketsForEventFiltersByStatusWhenGiven() throws Exception {
        Ticket ticket = new Ticket();
        ticket.setId(2L);
        ticket.setStatus(TicketStatus.AVAILABLE);
        when(ticketRepository.findByEventIdAndStatus(3L, TicketStatus.AVAILABLE)).thenReturn(List.of(ticket));

        mockMvc.perform(get("/events/3/tickets").param("status", "AVAILABLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(2));
    }
}
