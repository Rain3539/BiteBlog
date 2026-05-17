package com.biteblog.rank.service;

import com.biteblog.rank.config.RankRabbitConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
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
    public void onInteraction(Map<String, Object> event) {
        Long noteId = toLong(event.get("noteId"));
        String type = String.valueOf(event.getOrDefault("type", "view"));
        rankService.increaseByInteraction(noteId, type);
        log.info("Rank interaction event consumed: noteId={}, type={}", noteId, type);
    }

    private Map<String, Object> parseEvent(Message message, String eventName) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            return objectMapper.readValue(body, EVENT_TYPE);
        } catch (Exception e) {
            log.error("Failed to parse {} message", eventName, e);
            throw new RuntimeException(e);
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
