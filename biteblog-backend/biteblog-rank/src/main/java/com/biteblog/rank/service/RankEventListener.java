package com.biteblog.rank.service;

import com.biteblog.rank.config.RankRabbitConfig;
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
public class RankEventListener {
    private static final TypeReference<Map<String, Object>> EVENT_TYPE = new TypeReference<>() {};

    private final RankService rankService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = RankRabbitConfig.NOTE_PUBLISHED_QUEUE)
    public void onNotePublished(Message message) {
        Map<String, Object> event = parseEvent(message, "note.published");
        Long noteId = toLong(event.get("noteId"));
        rankService.addInitialScore(noteId);
        log.info("Rank note published event consumed: noteId={}", noteId);
    }

    @RabbitListener(queues = RankRabbitConfig.NOTE_DELETED_QUEUE)
    public void onNoteDeleted(Message message) {
        Map<String, Object> event = parseEvent(message, "note.deleted");
        Long noteId = toLong(event.get("noteId"));
        rankService.removeNote(noteId);
        log.info("Rank note deleted event consumed: noteId={}", noteId);
    }

    @RabbitListener(queues = RankRabbitConfig.INTERACTION_QUEUE)
    public void onInteraction(Message message) {
        Map<String, Object> event = parseEvent(message, "interaction");
        Long noteId = toLong(event.get("noteId"));
        String type = String.valueOf(event.getOrDefault("type", "view"));
        rankService.increaseByInteraction(noteId, type);
        log.info("Rank interaction event consumed: noteId={}, type={}, action={}",
                noteId, type, event.getOrDefault("action", "add"));
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
            log.error("Failed to parse {} message", eventName, e);
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
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }
}
