package com.biteblog.post.service;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PostEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    private static final String EXCHANGE = "biteblog.post";
    private static final String ROUTING_KEY_PUBLISHED = "note.published";
    private static final String ROUTING_KEY_DELETED = "note.deleted";

    public void publishNoteCreated(Long noteId, Long authorId) {
        Map<String, Object> event = new HashMap<>();
        event.put("noteId", noteId);
        event.put("authorId", authorId);
        event.put("timestamp", System.currentTimeMillis());
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY_PUBLISHED, event);
    }

    public void publishNoteDeleted(Long noteId) {
        Map<String, Object> event = new HashMap<>();
        event.put("noteId", noteId);
        event.put("timestamp", System.currentTimeMillis());
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY_DELETED, event);
    }
}
