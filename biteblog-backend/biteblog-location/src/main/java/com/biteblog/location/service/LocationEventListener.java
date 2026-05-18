package com.biteblog.location.service;

import com.biteblog.location.config.LocationRabbitConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LocationEventListener {
    private final LocationService locationService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = LocationRabbitConfig.NOTE_PUBLISHED_QUEUE)
    public void onNotePublished(String message) {
        try {
            Map<String, Object> event = objectMapper.readValue(message, Map.class);
            Long noteId = toLong(event.get("noteId"));
            locationService.addNoteLocation(noteId);
            log.info("Location note published event consumed: noteId={}", noteId);
        } catch (Exception e) {
            log.error("Failed to process note.published event: {}", e.getMessage());
        }
    }

    @RabbitListener(queues = LocationRabbitConfig.NOTE_DELETED_QUEUE)
    public void onNoteDeleted(String message) {
        try {
            Map<String, Object> event = objectMapper.readValue(message, Map.class);
            Long noteId = toLong(event.get("noteId"));
            locationService.removeNoteLocation(noteId);
            log.info("Location note deleted event consumed: noteId={}", noteId);
        } catch (Exception e) {
            log.error("Failed to process note.deleted event: {}", e.getMessage());
        }
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
