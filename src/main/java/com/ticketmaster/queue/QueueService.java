package com.ticketmaster.queue;

import com.ticketmaster.event.EventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class QueueService {
    private static final String ACTIVE_EVENTS_KEY = "queue:active-events";

    @Autowired
    private RedisScript<List> admitCleanupScript;
    @Autowired
    private RedisScript<Long> enqueueScript;
    @Autowired
    private EventService eventService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value("${queue.admit-rate:500}")
    private long admitRate;
    @Value("${queue.admit-interval-ms:1000}")
    private long admitIntervalMs;

    public String enqueue(Long eventId) {
        // chk if event exists AND is on sale
        eventService.getEventIfOnSale(eventId);

        String eventKey = getEventKey(eventId);
        String token = UUID.randomUUID()
                           .toString();

        Long queueSize = stringRedisTemplate.opsForZSet()
                                            .zCard(eventKey);
        // escape hatch for empty queue
        if (queueSize == null || queueSize == 0) {
            String eventAdmittedCountKey = getEventAdmittedCountKey(eventKey);
            Long admittedCount = stringRedisTemplate.opsForValue()
                                                    .increment(eventAdmittedCountKey);
            // window self-clears every admitIntervalMs so the escape hatch reopens once traffic calms down
            if (admittedCount == 1) stringRedisTemplate.expire(eventAdmittedCountKey,
                                                               Duration.ofMillis(admitIntervalMs));

            if (admittedCount <= admitRate) {
                grantAccess(eventId, token);
                return token;
            }
        }

        // atomic via lua script so admit()'s cleanup can never observe this event registered but not yet queued (or
        // vice versa)
        stringRedisTemplate.execute(enqueueScript,
                                    List.of(ACTIVE_EVENTS_KEY, getEventQueueSequenceKey(eventKey), eventKey),
                                    eventId.toString(), token);
        return token;
    }

    public Long getPosition(Long eventId, String token) {
        return stringRedisTemplate.opsForZSet()
                                  .rank(getEventKey(eventId), token);
    }

    @Scheduled(fixedDelayString = "${queue.admit-interval-ms:1000}")
    public void admit() {
        Set<String> activeEvents = stringRedisTemplate.opsForSet()
                                                      .members(ACTIVE_EVENTS_KEY);
        if (activeEvents == null || activeEvents.isEmpty()) return;

        for (String eventId : activeEvents) {
            String eventKey = getEventKey(Long.valueOf(eventId));

            List<String> popped = stringRedisTemplate.execute(admitCleanupScript, List.of(eventKey, ACTIVE_EVENTS_KEY),
                                                              String.valueOf(admitRate), eventId);

            // ZPOPMIN's flat reply alternates member/score - popped[i] is the token, popped[i+1] its sequence number
            for (int i = 0; i < popped.size(); i += 2) {
                String token = popped.get(i);
                stringRedisTemplate.opsForValue()
                                   .set(getAccessKey(Long.valueOf(eventId), token), "1", Duration.ofMinutes(10));
            }
        }
    }

    public QueueStatusResponse checkStatus(Long eventId, String token) {
        if (hasAccess(eventId, token)) return new QueueStatusResponse(QueueStatus.ADMITTED, null);

        Long position = getPosition(eventId, token);
        if (position != null) return new QueueStatusResponse(QueueStatus.WAITING, position);

        return new QueueStatusResponse(QueueStatus.INVALID, null);
    }

    public boolean hasAccess(Long eventId, String token) {
        if (token == null || token.isBlank()) return false;
        return stringRedisTemplate.hasKey(getAccessKey(eventId, token));
    }

    public void purgeEvent(long eventId) {
        String eventKey = getEventKey(eventId);

        // active event
        stringRedisTemplate.opsForSet()
                           .remove(ACTIVE_EVENTS_KEY, String.valueOf(eventId));
        // queue sequence
        stringRedisTemplate.delete(getEventQueueSequenceKey(eventKey));
        // admitted count
        stringRedisTemplate.delete(getEventAdmittedCountKey(eventKey));
        // queue
        stringRedisTemplate.delete(eventKey);
        // admitted access tokens
        // standard delete will block redis while scanning
        // chunk using cursor so we don't block
        ScanOptions options = ScanOptions.scanOptions()
                                         .match(getAccessKeyPrefix(eventId) + "*")
                                         .count(1000)
                                         .build();
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            List<String> batch = new ArrayList<>();
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() == 1000) {
                    stringRedisTemplate.unlink(batch);
                    batch.clear();
                }
            }
            // del is synchronous: O(n)
            // unlink is async: O(1)
            // del blocks until memory is freed
            // unlink removes key instantly, and memory is freed in background
            if (!batch.isEmpty()) stringRedisTemplate.unlink(batch);
        }
    }

    private void grantAccess(Long eventId, String token) {
        stringRedisTemplate.opsForValue()
                           .set(getAccessKey(eventId, token), "1", Duration.ofMinutes(10));
    }

    private String getAccessKeyPrefix(Long eventId) {
        return "access:" + eventId.toString() + ":";
    }

    private String getAccessKey(Long eventId, String token) {
        return getAccessKeyPrefix(eventId) + token;
    }

    private String getEventAdmittedCountKey(String eventKey) {
        return eventKey + ":admitted-count";
    }

    private String getEventKey(Long eventId) {
        return "queue:" + eventId;
    }

    private String getEventQueueSequenceKey(String eventKey) {
        return eventKey + ":seq";
    }
}
