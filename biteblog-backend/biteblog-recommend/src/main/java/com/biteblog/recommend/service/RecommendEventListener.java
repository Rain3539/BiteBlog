package com.biteblog.recommend.service;

import com.biteblog.recommend.config.RecommendRabbitConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendEventListener {

    private static final TypeReference<Map<String, Object>> EVENT_TYPE = new TypeReference<>() {};

    private final RecommendPrecomputeService precomputeService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = RecommendRabbitConfig.NOTE_PUBLISHED_QUEUE)
    public void onNotePublished(Message message) {
        Map<String, Object> event = parseEvent(message, "note.published");
        Long noteId = toLong(event.get("noteId"));
        if (noteId == null) {
            log.warn("Recommend skip note.published: missing noteId, event={}", event);
            return;
        }
        Map<String, Object> result = precomputeService.refreshNote(noteId);
        log.info("Recommend note.published consumed: noteId={}, result={}", noteId, result);
    }

    @RabbitListener(queues = RecommendRabbitConfig.NOTE_DELETED_QUEUE)
    public void onNoteDeleted(Message message) {
        Map<String, Object> event = parseEvent(message, "note.deleted");
        Long noteId = toLong(event.get("noteId"));
        if (noteId == null) {
            log.warn("Recommend skip note.deleted: missing noteId, event={}", event);
            return;
        }
        Map<String, Object> result = precomputeService.removeNote(noteId);
        log.info("Recommend note.deleted consumed: noteId={}, result={}", noteId, result);
    }

    @RabbitListener(queues = RecommendRabbitConfig.INTERACTION_QUEUE)
    public void onInteraction(Message message) {
        Map<String, Object> event = parseEvent(message, "interaction");
        Long noteId = toLong(event.get("noteId"));
        if (noteId == null) {
            log.warn("Recommend skip interaction: missing noteId, event={}", event);
            return;
        }
        Map<String, Object> result = precomputeService.refreshAfterInteraction(noteId);
        log.info("Recommend interaction consumed: noteId={}, type={}, action={}, result={}",
                noteId, event.get("type"), event.getOrDefault("action", "add"), result);
    }

    private Map<String, Object> parseEvent(Message message, String eventName) {
        try {
            byte[] body = message.getBody();
            String text = new String(body, StandardCharsets.UTF_8).trim();
            if (text.startsWith("{")) {
                return objectMapper.readValue(text, EVENT_TYPE);
            }
            return deserializeMap(body);
        } catch (Exception e) {
            log.error("Failed to parse recommend {} message", eventName, e);
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deserializeMap(byte[] body) throws Exception {
        try (ObjectInputStream inputStream = new ObjectInputStream(new ByteArrayInputStream(body))) {
            Object object = inputStream.readObject();
            if (object instanceof Map<?, ?> rawMap) {
                Map<String, Object> result = new LinkedHashMap<>();
                rawMap.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
        }
        return Map.of();
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
