package com.ticketmaster.queue;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
