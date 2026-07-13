package com.ticketmaster.queue;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class QueueService {
    @Value("${queue.admit-rate:500}")
    private long admitRate;

    @Value("${queue.admit-interval-ms:1000}")
    private long admitIntervalMs;

    @Autowired
    private RedisScript<Long> enqueueScript;

    @Autowired
    private RedisScript<List> admitCleanupScript;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private final String ACTIVE_EVENTS_KEY = "queue:active-events";

    public String enqueue(Long eventId) {
        String eventKey = getEventKey(eventId);
        String eventAdmittedCountKey = getEventAdmittedCountKey(eventKey);
        String token = UUID.randomUUID()
                           .toString();

        Long admittedCount = stringRedisTemplate.opsForValue()
                                                .increment(eventAdmittedCountKey);
        // window self-clears every admitIntervalMs so the escape hatch reopens once traffic calms down
        if (admittedCount == 1) stringRedisTemplate.expire(eventAdmittedCountKey, Duration.ofMillis(admitIntervalMs));

        Long queueSize = stringRedisTemplate.opsForZSet()
                                            .zCard(eventKey);
        // escape hatch: only bypass the real queue if nobody's already waiting AND we're still under the rate this window
        if ((queueSize == null || queueSize == 0) && admittedCount <= admitRate) {
            grantAccess(eventId, token);
            return token;
        }

        // atomic so admit()'s cleanup can never observe this event registered but not yet queued (or vice versa)
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

    private void grantAccess(Long eventId, String token) {
        stringRedisTemplate.opsForValue()
                           .set(getAccessKey(eventId, token), "1", Duration.ofMinutes(10));
    }

    private String getAccessKey(Long eventId, String token) {
        return "access:" + eventId.toString() + ":" + token;
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
