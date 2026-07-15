package com.ticketmaster.booking;

import com.ticketmaster.event.Event;
import com.ticketmaster.event.EventService;
import com.ticketmaster.queue.QueueAccessRequiredException;
import com.ticketmaster.queue.QueueService;
import com.ticketmaster.ticket.*;
import com.ticketmaster.user.UserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;

@Service
@Slf4j  // lombok generates LoggerFactory.getLogger()
public class BookingService {
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private EventService eventService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private QueueService queueService;
    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private UserRepository userRepository;

    @Value("${booking.max-tickets-per-user:4}")
    private int maxTicketsPerUser;
    private TransactionTemplate transactionTemplate;

    @PostConstruct
    void init() {
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public Booking hold(Long userId, Long eventId, List<Long> ticketIds, String idempotencyKey, String accessToken) {
        // 1. ensure event is onsale
        Event event = eventService.getEventIfOnSale(eventId);

        // 2. check if event requires queueing
        if (event.isRequiresQueue()) {
            if (accessToken == null || accessToken.isBlank() || !queueService.hasAccess(eventId, accessToken))
                throw new QueueAccessRequiredException("Access Denied");
        }

        // 2. check against booking ticket limit
        List<BookingStatus> openStatuses = new ArrayList<>(List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED));
        int openTickets = bookingRepository.findByUserIdAndEventIdAndStatusIn(userId, eventId, openStatuses)
                                           .stream()
                                           .mapToInt(b -> b.getTickets()
                                                           .size())
                                           .sum();
        if ((openTickets + ticketIds.size()) > maxTicketsPerUser)
            throw new TicketLimitedExceededException("Maximum number of tickets per user exceeded");

        // 3. check if booking exists
        Optional<Booking> existingBooking = bookingRepository.findByIdempotencyKey(idempotencyKey);
        return existingBooking.orElseGet(() -> {

            // 4. lock ticket rows on read
            List<Ticket> tickets = ticketRepository.findByIdIn(ticketIds);
            if (tickets.size() != ticketIds.size()) throw new TicketUnavailableException();

            // 5. validate availability
            for (Ticket ticket : tickets)
                if (ticket.getStatus() != TicketStatus.AVAILABLE)
                    throw new TicketUnavailableException("Ticket %d unavailable".formatted(ticket.getId()));

            int totalCents = tickets.stream()
                                    .mapToInt(Ticket::getPriceCents)
                                    .sum();

            // 6. book!
            ZonedDateTime expiresAt = ZonedDateTime.now()
                                                   .plusMinutes(10);
            Booking booking = new Booking();
            booking.setUser(userRepository.findById(userId)
                                          .orElseThrow(() -> new NoSuchElementException("User not found: " + userId)));
            booking.setEvent(event);
            booking.setTickets(tickets);
            booking.setStatus(BookingStatus.PENDING);
            booking.setTotalCents(totalCents);
            booking.setIdempotencyKey(idempotencyKey);
            booking.setExpiresAt(expiresAt.toInstant());
            bookingRepository.save(booking);

            // 7. hold tickets
            for (Ticket ticket : tickets) {
                ticket.setStatus(TicketStatus.HELD);
                ticket.setHoldExpiresAt(expiresAt);
                ticket.setBooking(booking);
            }
            ticketRepository.saveAll(tickets);

            return booking;
        });
    }

    @Transactional
    public Booking confirm(long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                                           .orElseThrow(() -> new NoSuchElementException(
                                                   "Booking not found: " + bookingId));

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

        eventService.markSoldOutIfLastTicketBooked(booking.getEvent());

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
                    // bookings loaded above are already detached
                    // since @OneToMany is Lazy, List<Ticket> tickets is Lazy
                    // calling it results in LazyInitializationException ("could not initialize proxy — no Session")
                    // so we need to reload the booking to get the tickets
                    Booking attachedBooking = bookingRepository.findById(booking.getId())
                                                               .orElseThrow(() -> new NoSuchElementException(
                                                                       "Booking not found: " + booking.getId()));
                    attachedBooking.setStatus(BookingStatus.EXPIRED);
                    for (Ticket ticket : attachedBooking.getTickets()) {
                        ticket.setStatus(TicketStatus.AVAILABLE);
                        ticket.setHoldExpiresAt(null);
                    }
                    // entities loaded within the transaction are attached
                    // thus no need to explicitly call repository.save()
                    return null;
                });
            } catch (Exception e) {
                log.error("Failed to expire booking {}", booking.getId(), e);
            }
        }
    }

    @Transactional
    public Booking cancel(long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                                           .orElseThrow(() -> new NoSuchElementException(
                                                   "Booking not found: " + bookingId));

        // BookingStatus.CONFIRMED for refund flow
        if (!EnumSet.of(BookingStatus.PENDING, BookingStatus.CONFIRMED)
                    .contains(booking.getStatus())) throw new InvalidBookingState("Booking not pending or confirmed");

        booking.setStatus(BookingStatus.CANCELLED);
        for (Ticket ticket : booking.getTickets()) {
            ticket.setStatus(TicketStatus.AVAILABLE);
            ticket.setHoldExpiresAt(null);
        }
        ticketRepository.saveAll(booking.getTickets());
        bookingRepository.save(booking);

        return booking;
    }
}
