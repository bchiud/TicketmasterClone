package com.ticketmaster.queue;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

@Service
public class QueueService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value("${queue.admit-rate:500}")
    private long admitRate;

    public String enqueue(Long eventId) {
        // register event
        stringRedisTemplate.opsForSet()
                           .add("queue:active-events", eventId.toString());

        String token = UUID.randomUUID()
                           .toString();
        Long sequence = stringRedisTemplate.opsForValue()
                                           .increment(getKey(eventId) + ":seq");
        stringRedisTemplate.opsForZSet()
                           .add(getKey(eventId), token, sequence);
        return token;
    }

    public Long getPosition(Long eventId, String token) {
        return stringRedisTemplate.opsForZSet()
                                  .rank(getKey(eventId), token);
    }

    @Scheduled(fixedDelayString = "${queue.admit-interval-ms:1000}")
    public void admit() {
        Set<String> activeEvents = stringRedisTemplate.opsForSet()
                                                      .members("queue:active-events");
        if (activeEvents == null || activeEvents.isEmpty())
            return;

        for (String eventId : activeEvents) {
            String key = getKey(Long.valueOf(eventId));

            Set<ZSetOperations.TypedTuple<String>> popped = stringRedisTemplate.opsForZSet()
                                                                               .popMin(key, admitRate);

            if (popped == null || popped.isEmpty())
                continue;

            for (ZSetOperations.TypedTuple<String> entry : popped) {
                stringRedisTemplate.opsForValue()
                                   .set("access:" + entry.getValue(), "1", Duration.ofMinutes(10));
            }
        }
    }

    public QueueStatusResponse checkStatus(Long eventId, String token)
    {
        if (hasAccess(token))
            return new QueueStatusResponse(QueueStatus.ADMITTED, null);

        Long position = getPosition(eventId, token);
        if (position != null)
            return new QueueStatusResponse(QueueStatus.WAITING, position);

        return new QueueStatusResponse(QueueStatus.INVALID, null);
    }

    public boolean hasAccess(String token) {
        if (token == null || token.isBlank())
            return false;
        return stringRedisTemplate.hasKey("access:" + token);
    }

    private String getKey(Long eventId) {
        return "queue:" + eventId;
    }
}
