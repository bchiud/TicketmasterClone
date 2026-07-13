package com.ticketmaster.queue;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QueueController {
    @Autowired
    private QueueService queueService;

    @PostMapping("/events/{id}/queue")
    public String enqueue(@PathVariable Long id) {
        return queueService.enqueue(id);
    }

    @GetMapping("/events/{eventId}/queue/{token}")
    public QueueStatusResponse getStatus(@PathVariable Long eventId, @PathVariable String token) {
        return queueService.checkStatus(eventId, token);
    }
}
