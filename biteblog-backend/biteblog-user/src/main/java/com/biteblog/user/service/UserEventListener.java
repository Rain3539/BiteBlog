package com.biteblog.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventListener {

    private final UserService userService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "biteblog.user.like")
    public void handleLikeEvent(String message) {
        try {
            Map<String, Object> event = objectMapper.readValue(message, Map.class);
            Long authorId = toLong(event.get("authorId"));
            String action = (String) event.get("action");

            if (authorId == null || action == null) return;

            if ("add".equals(action)) {
                userService.incrLikeCount(authorId);
            } else if ("remove".equals(action)) {
                userService.decrLikeCount(authorId);
            }
        } catch (Exception e) {
            log.warn("Failed to handle like event: {}", message, e);
        }
    }

    private Long toLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) return Long.parseLong((String) value);
        return null;
    }
}
