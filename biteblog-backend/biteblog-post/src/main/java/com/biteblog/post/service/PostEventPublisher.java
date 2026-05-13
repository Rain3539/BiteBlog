package com.biteblog.post.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PostEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    private static final String EXCHANGE = "biteblog.post";
    private static final String ROUTING_KEY_PUBLISHED = "note.published";
    private static final String ROUTING_KEY_DELETED = "note.deleted";

    public void publishNoteCreated(Long noteId, Long authorId) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("noteId", noteId);
            event.put("authorId", authorId);
            event.put("timestamp", System.currentTimeMillis());
            String json = objectMapper.writeValueAsString(event);
            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY_PUBLISHED, json);
        } catch (Exception e) {
            // 日志由上层处理
        }
    }

    public void publishNoteDeleted(Long noteId) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("noteId", noteId);
            event.put("timestamp", System.currentTimeMillis());
            String json = objectMapper.writeValueAsString(event);
            rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY_DELETED, json);
        } catch (Exception e) {
            // 日志由上层处理
        }
    }
}
