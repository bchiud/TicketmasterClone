package com.ticketmaster.event;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.time.ZonedDateTime;

@Getter
@Setter
public class EventCreateRequest {
    @NotBlank
    String name;
    @NotBlank
    String performer;
    @NotNull
    Long venueId;
    @NotNull
    ZonedDateTime startsAt;
    @NotNull
    ZonedDateTime onSaleAt;
    @NotNull
    @Positive
    Integer priceCents;
    boolean requiresQueue = false;
}
