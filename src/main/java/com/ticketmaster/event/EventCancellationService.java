package com.ticketmaster.event;

import com.ticketmaster.booking.Booking;
import com.ticketmaster.booking.BookingRepository;
import com.ticketmaster.booking.BookingService;
import com.ticketmaster.booking.BookingStatus;
import com.ticketmaster.event.exception.EventAlreadyCancelledException;
import com.ticketmaster.payment.PaymentService;
import com.ticketmaster.queue.QueueService;
import com.ticketmaster.ticket.TicketService;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

// this is pulled out of EventService to prevent circular dependencies
// most Services depend on EventService
// but Event(CancellationService).cancel(Event) depends on most Services
@Service
public class EventCancellationService {
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private BookingService bookingService;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private QueueService queueService;
    @Autowired
    private TicketService ticketService;

    @Transactional
    public Event cancelEvent(Long eventId) {
        Event event = eventRepository.findById(eventId)
                                     .orElseThrow(() -> new NoSuchElementException("Event not " + "found: " + eventId));

        if (event.getStatus() == EventStatus.CANCELLED)
            throw new EventAlreadyCancelledException("Event already cancelled");

        event.setStatus(EventStatus.CANCELLED);
        eventRepository.save(event);

        List<Booking> bookings = bookingRepository.findByEventIdAndStatusIn(eventId,
                                                                            List.of(BookingStatus.PENDING,
                                                                                    BookingStatus.CONFIRMED));
        for (Booking booking : bookings) {
            if (booking.getStatus() == BookingStatus.CONFIRMED) paymentService.refund(booking.getId());
            else bookingService.cancel(booking.getId());
        }

        ticketService.cancelTicketsbyEventId(eventId);

        queueService.purgeEvent(eventId);

        return event;
    }
}
