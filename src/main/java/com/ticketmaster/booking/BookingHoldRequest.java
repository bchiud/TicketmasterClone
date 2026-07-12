package com.ticketmaster.booking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class BookingHoldRequest {
    @NotNull
    private Long userId;
    @NotNull
    private Long eventId;
    @NotEmpty
    private List<Long> ticketIds;
    @NotBlank
    private String idempotencyKey;
}
