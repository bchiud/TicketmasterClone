package com.ticketmaster.booking;

import com.ticketmaster.event.EventRepository;
import com.ticketmaster.ticket.Ticket;
import com.ticketmaster.ticket.TicketRepository;
import com.ticketmaster.ticket.TicketStatus;
import com.ticketmaster.ticket.TicketUnavailableException;
import com.ticketmaster.user.UserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@Service
@Slf4j  // lombok generates LoggerFactory.getLogger()
public class BookingService {
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    @PostConstruct
    void init() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public Booking hold(Long userId, Long eventId, List<Long> ticketIds, String idempotencyKey) {
        // 1. check if booking exists
        Booking booking = bookingRepository.findByIdempotencyKey(idempotencyKey);
        if (booking != null) return booking;

        // 2. lock tickets
        List<Ticket> tickets = ticketRepository.findByIdIn(ticketIds);
        if (tickets.size() != ticketIds.size()) throw new TicketUnavailableException();

        // 3. validate availability
        for (Ticket ticket : tickets)
            if (ticket.getStatus() != TicketStatus.AVAILABLE) throw new TicketUnavailableException(
                    "Ticket %d unavailable".formatted(ticket.getId()));

        // 4. hold tickets
        ZonedDateTime expiresAt = ZonedDateTime.now()
                                               .plusMinutes(10);
        int totalCents = 0;
        for (Ticket ticket : tickets) {
            ticket.setStatus(TicketStatus.HELD);
            ticket.setHoldExpiresAt(expiresAt);
            totalCents += ticket.getPriceCents();
        }
        ticketRepository.saveAll(tickets);

        // 5. book!
        booking = new Booking();
        booking.setUser(userRepository.findById(userId)
                                      .orElseThrow(() -> new NoSuchElementException("User not found: " + userId)));
        booking.setEvent(eventRepository.findById(eventId)
                                        .orElseThrow(() -> new NoSuchElementException("Event not found: " + eventId)));
        booking.setTickets(tickets);
        booking.setStatus(BookingStatus.PENDING);
        booking.setTotalCents(totalCents);
        booking.setIdempotencyKey(idempotencyKey);
        booking.setExpiresAt(expiresAt.toInstant());
        bookingRepository.save(booking);
        return booking;
    }

    @Transactional
    public Booking confirm(long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                                           .orElseThrow(
                                                   () -> new NoSuchElementException("Booking not found: " + bookingId));

        if (!booking.getStatus()
                    .equals(BookingStatus.PENDING)) throw new InvalidBookingState("Booking not pending");
        if (booking.getExpiresAt()
                   .isBefore(Instant.now())) throw new InvalidBookingState("Booking expired");

        booking.setStatus(BookingStatus.CONFIRMED);
        for (Ticket ticket : booking.getTickets()) {
            ticket.setStatus(TicketStatus.BOOKED);
            ticket.setHoldExpiresAt(null);
        }

        ticketRepository.saveAll(booking.getTickets());
        bookingRepository.save(booking);

        return booking;
    }

    // fixedDelays waits X ms after each run finishes
    // vs. fixedRate which starts every X ms
    // this prevents overlap
    @Scheduled(fixedDelayString = "${booking.expiry-sweep-interval-ms:30000}")
    public void expire() {
        List<Booking> bookings = bookingRepository.findByStatusAndExpiresAtBefore(BookingStatus.PENDING, Instant.now());
        // do one-by-one so we don't roll back all the bookings if one fails
        for (Booking booking : bookings) {
            try {
                transactionTemplate.execute(status -> {
                    booking.setStatus(BookingStatus.EXPIRED);
                    for (Ticket ticket : booking.getTickets()) {
                        ticket.setStatus(TicketStatus.AVAILABLE);
                        ticket.setHoldExpiresAt(null);
                    }
                    ticketRepository.saveAll(booking.getTickets());
                    bookingRepository.save(booking);
                    return null;
                });
            } catch (Exception e) {
                log.error("Failed to expire booking {}", booking.getId(), e);
            }
        }
    }
}
