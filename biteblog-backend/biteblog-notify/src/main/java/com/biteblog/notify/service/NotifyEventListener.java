package com.biteblog.notify.service;

import com.biteblog.notify.config.NotifyRabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 与 RankEventListener 相同消息体；独立队列 notify.interaction.queue
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyEventListener {

    private final NotifyService notifyService;

    @RabbitListener(queues = NotifyRabbitConfig.NOTIFY_INTERACTION_QUEUE)
    public void onInteraction(Map<String, Object> event,
                              @Header(value = AmqpHeaders.RECEIVED_ROUTING_KEY, required = false) String routingKey) {
        try {
            notifyService.handleInteractionEvent(event, routingKey);
            log.info("Notify interaction consumed routingKey={}, noteId={}", routingKey, event.get("noteId"));
        } catch (Exception e) {
            // 不再向上抛：避免消息无法 ack、在队列里无限重投（Rabbit 上 Unacked 飙高、ack=0）。
            // 生产环境可改为 DLQ + 告警；开发期先打日志便于排查。
            log.error("Notify interaction handle failed (message will be acked). routingKey={}, event={}", routingKey, event, e);
        }
    }
}
