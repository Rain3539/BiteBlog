package com.biteblog.rank.service;

import com.biteblog.rank.config.RankRabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RankEventListener {
    private final RankService rankService;

    @RabbitListener(queues = RankRabbitConfig.NOTE_PUBLISHED_QUEUE)
    public void onNotePublished(Map<String, Object> event) {
        Long noteId = toLong(event.get("noteId"));
        rankService.addInitialScore(noteId);
        log.info("Rank note published event consumed: noteId={}", noteId);
    }

    @RabbitListener(queues = RankRabbitConfig.NOTE_DELETED_QUEUE)
    public void onNoteDeleted(Map<String, Object> event) {
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
