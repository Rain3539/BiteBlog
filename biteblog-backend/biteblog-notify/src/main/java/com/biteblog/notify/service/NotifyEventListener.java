package com.biteblog.notify.service;

import com.biteblog.notify.config.NotifyRabbitConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyEventListener {

    private static final TypeReference<Map<String, Object>> EVENT_TYPE = new TypeReference<>() {};

    private final NotifyService notifyService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = NotifyRabbitConfig.NOTIFY_NOTE_PUBLISHED_QUEUE,
                    ackMode = "MANUAL")
    public void onNotePublished(Message message,
                                Channel channel,
                                @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        Map<String, Object> event = null;
        try {
            event = parseEvent(message);
            Long noteId = toLong(event.get("noteId"));
            Long authorId = toLong(event.get("authorId"));
            notifyService.handleNotePublished(noteId, authorId);
            log.info("Notify consumed note.published noteId={}, authorId={}", noteId, authorId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("Notify note.published failed, routing to DLQ. event={}", event, e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException ioException) {
                log.error("basicNack failed", ioException);
            }
        }
    }

    @RabbitListener(queues = NotifyRabbitConfig.NOTIFY_INTERACTION_QUEUE,
                    ackMode = "MANUAL")
    public void onInteraction(Message message,
                              Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                              @Header(value = AmqpHeaders.RECEIVED_ROUTING_KEY, required = false) String routingKey) {
        Map<String, Object> event = null;
        try {
            event = parseEvent(message);
            notifyService.handleInteractionEvent(event, routingKey);
            log.info("Notify consumed routingKey={}, noteId={}, action={}",
                    routingKey, event.get("noteId"), event.getOrDefault("action", "add"));
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("Notify handle failed, routing to DLQ. routingKey={}, event={}", routingKey, event, e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException ioException) {
                log.error("basicNack failed", ioException);
            }
        }
    }

    private Map<String, Object> parseEvent(Message message) throws Exception {
        byte[] body = message.getBody();
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (text.startsWith("{")) {
            return objectMapper.readValue(text, EVENT_TYPE);
        }
        return deserializeMap(body);
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.valueOf(String.valueOf(value));
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
}
