package com.ticketmaster.queue;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QueueStatusResponse {
    public QueueStatus queueStatus;
    public Long position;

    public QueueStatusResponse(QueueStatus queueStatus, Long position) {
        this.queueStatus = queueStatus;
        this.position = position;
    }
}
