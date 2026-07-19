package com.ticketmaster.event;

import java.time.ZonedDateTime;

public record EventResponse(Long id, String name, String performer, Long venueId, ZonedDateTime startsAt,
                            ZonedDateTime onSaleAt, EventStatus status, boolean requiresQueue) {
    public static EventResponse from(Event e) {
        return new EventResponse(e.getId(),
                                 e.getName(),
                                 e.getPerformer(),
                                 e.getVenue().getId(),
                                 e.getStartsAt(),
                                 e.getOnSaleAt(),
                                 e.getStatus(),
                                 e.isRequiresQueue());
    }
}
