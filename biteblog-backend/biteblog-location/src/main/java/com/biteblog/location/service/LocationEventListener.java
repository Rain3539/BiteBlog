package com.biteblog.location.service;

import com.biteblog.location.config.LocationRabbitConfig;
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
public class LocationEventListener {
    private static final TypeReference<Map<String, Object>> EVENT_TYPE = new TypeReference<>() {};

    private final LocationService locationService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = LocationRabbitConfig.NOTE_PUBLISHED_QUEUE)
    public void onNotePublished(Message message) {
        Map<String, Object> event = parseEvent(message, "note.published");
        Long noteId = toLong(event.get("noteId"));
        if (noteId == null) {
            return;
        }
        locationService.addNoteLocation(noteId);
        log.info("Location note published event consumed: noteId={}", noteId);
    }

    @RabbitListener(queues = LocationRabbitConfig.NOTE_DELETED_QUEUE)
    public void onNoteDeleted(Message message) {
        Map<String, Object> event = parseEvent(message, "note.deleted");
        Long noteId = toLong(event.get("noteId"));
        if (noteId == null) {
            return;
        }
        locationService.removeNoteLocation(noteId);
        log.info("Location note deleted event consumed: noteId={}", noteId);
    }

    private Map<String, Object> parseEvent(Message message, String eventName) {
        byte[] body = message.getBody();
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (text.startsWith("{")) {
            try {
                return objectMapper.readValue(text, EVENT_TYPE);
            } catch (Exception e) {
                log.error("Failed to parse {} message as JSON: text={}", eventName, text, e);
                throw new RuntimeException("Failed to parse " + eventName + " message", e);
            }
        }
        try {
            return deserializeMap(body);
        } catch (Exception e) {
            log.error("Failed to deserialize {} message", eventName, e);
            throw new RuntimeException("Failed to deserialize " + eventName + " message", e);
        }
    }

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
