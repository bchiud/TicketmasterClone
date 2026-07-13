package com.ticketmaster.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

@Configuration
public class RedisScriptConfig {
    @Bean
    public RedisScript<Long> enqueueScript() {
        return RedisScript.of(new ClassPathResource("scripts/enqueue.lua"), Long.class);
    }

    @Bean
    public RedisScript<List> admitCleanupScript() {
        return RedisScript.of(new ClassPathResource("scripts/admitCleanup.lua"), List.class);
    }
}
