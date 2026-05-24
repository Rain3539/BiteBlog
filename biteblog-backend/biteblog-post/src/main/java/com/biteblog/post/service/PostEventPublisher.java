package com.biteblog.post.service;

import com.biteblog.post.config.PostRabbitConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    private static final String ROUTING_KEY_PUBLISHED = "note.published";
    private static final String ROUTING_KEY_DELETED = "note.deleted";

    public void publishNoteCreated(Long noteId, Long authorId) {
        Map<String, Object> event = new HashMap<>();
        event.put("noteId", noteId);
        event.put("authorId", authorId);
        event.put("timestamp", System.currentTimeMillis());
        sendJsonAfterCommit(PostRabbitConfig.POST_EXCHANGE, ROUTING_KEY_PUBLISHED, event);
    }

    public void publishNoteDeleted(Long noteId) {
        Map<String, Object> event = new HashMap<>();
        event.put("noteId", noteId);
        event.put("timestamp", System.currentTimeMillis());
        sendJsonAfterCommit(PostRabbitConfig.POST_EXCHANGE, ROUTING_KEY_DELETED, event);
    }

    public void publishInteraction(Long noteId, Long userId, Long authorId, String type, String action) {
        publishInteraction(noteId, userId, authorId, type, action, null);
    }

    public void publishInteraction(Long noteId, Long userId, Long authorId, String type, String action,
                                 Map<String, Object> extras) {
        Map<String, Object> event = new HashMap<>();
        event.put("noteId", noteId);
        event.put("userId", userId);
        event.put("authorId", authorId);
        event.put("type", type);
        event.put("action", action);
        event.put("timestamp", System.currentTimeMillis());
        if (extras != null && !extras.isEmpty()) {
            event.putAll(extras);
        }
        sendJsonAfterCommit(PostRabbitConfig.INTERACTION_EXCHANGE, "interaction." + type, event);
    }

    private void sendJsonAfterCommit(String exchange, String routingKey, Map<String, Object> event) {
        Runnable sendTask = () -> sendJson(exchange, routingKey, event);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendTask.run();
                }
            });
            return;
        }
        sendTask.run();
    }

    private void sendJson(String exchange, String routingKey, Map<String, Object> event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            rabbitTemplate.convertAndSend(exchange, routingKey, json, message -> {
                message.getMessageProperties().setContentType(MessageProperties.CONTENT_TYPE_JSON);
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            });
        } catch (Exception e) {
            log.warn("Publish MQ event failed. exchange={}, routingKey={}, event={}", exchange, routingKey, event, e);
        }
    }
}
