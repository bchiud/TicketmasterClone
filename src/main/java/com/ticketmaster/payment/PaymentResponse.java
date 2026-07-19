package com.ticketmaster.payment;

import java.time.ZonedDateTime;

public record PaymentResponse(Long id, Long bookingId, Integer amountCents, PaymentStatus status,
                              ZonedDateTime createdAt) {
    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getId(),
                                   p.getBooking()
                                    .getId(),
                                   p.getAmountCents(),
                                   p.getStatus(),
                                   p.getCreatedAt());
    }
}
