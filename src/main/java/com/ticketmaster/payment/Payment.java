package com.ticketmaster.payment;

import com.ticketmaster.booking.Booking;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.ZonedDateTime;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // payments may fail
    @ManyToOne
    private Booking booking;

    private String providerRef;

    private Integer amountCents;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status = PaymentStatus.PENDING;

    @CreationTimestamp
    private ZonedDateTime createdAt;
}
