package com.ticketmaster.queue;

import com.ticketmaster.event.exception.EventNotOnSaleException;
import com.ticketmaster.queue.exception.RateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(QueueController.class)
class QueueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QueueService queueService;

    @Test
    void enqueueReturnsToken() throws Exception {
        when(queueService.enqueue(eq(42L), anyString())).thenReturn("token-abc");

        mockMvc.perform(post("/events/42/queue"))
               .andExpect(status().isOk())
               .andExpect(content().string("token-abc"));
    }

    @Test
    void enqueueReturns429WhenRateLimited() throws Exception {
        when(queueService.enqueue(eq(42L), anyString())).thenThrow(new RateLimitException("Rate limit exceeded"));

        mockMvc.perform(post("/events/42/queue"))
               .andExpect(status().isTooManyRequests())
               .andExpect(content().string("Rate limit exceeded"));
    }

    @Test
    void enqueueReturns409WhenEventNotOnSale() throws Exception {
        when(queueService.enqueue(eq(42L), anyString())).thenThrow(new EventNotOnSaleException("Event not on sale: 42"));

        mockMvc.perform(post("/events/42/queue"))
               .andExpect(status().isConflict())
               .andExpect(content().string("Event not on sale: 42"));
    }

    @Test
    void getStatusReturnsWaitingStatusWithPosition() throws Exception {
        when(queueService.checkStatus(42L, "token-abc"))
                .thenReturn(new QueueStatusResponse(QueueStatus.WAITING, 3L));

        mockMvc.perform(get("/events/42/queue/token-abc"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.queueStatus").value("WAITING"))
               .andExpect(jsonPath("$.position").value(3));
    }

    @Test
    void getStatusReturnsAdmittedStatus() throws Exception {
        when(queueService.checkStatus(42L, "token-abc"))
                .thenReturn(new QueueStatusResponse(QueueStatus.ADMITTED, null));

        mockMvc.perform(get("/events/42/queue/token-abc"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.queueStatus").value("ADMITTED"))
               .andExpect(jsonPath("$.position").value(nullValue()));
    }

    @Test
    void getStatusReturnsInvalidStatusForUnknownToken() throws Exception {
        when(queueService.checkStatus(42L, "bogus"))
                .thenReturn(new QueueStatusResponse(QueueStatus.INVALID, null));

        mockMvc.perform(get("/events/42/queue/bogus"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.queueStatus").value("INVALID"));
    }
}
