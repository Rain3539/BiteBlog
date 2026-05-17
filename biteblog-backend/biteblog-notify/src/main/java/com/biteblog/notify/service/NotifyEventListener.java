package com.biteblog.notify.service;

import com.biteblog.notify.config.NotifyRabbitConfig;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * 消费互动事件写入通知。
 * 使用手动 ack ：
 *   - 业务成功 → basicAck，消息正常确认；
 *   - 业务异常 → basicNack(requeue=false)，消息转投死信队列（DLQ），不无限重投。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyEventListener {

    private final NotifyService notifyService;

    @RabbitListener(queues = NotifyRabbitConfig.NOTIFY_INTERACTION_QUEUE,
                    ackMode = "MANUAL")
    public void onInteraction(Map<String, Object> event,
                              Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                              @Header(value = AmqpHeaders.RECEIVED_ROUTING_KEY, required = false) String routingKey) {
        try {
            notifyService.handleInteractionEvent(event, routingKey);
            log.info("Notify consumed routingKey={}, noteId={}", routingKey, event.get("noteId"));
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("Notify handle failed, routing to DLQ. routingKey={}, event={}", routingKey, event, e);
            try {
                // requeue=false：不重新入队，转投死信队列
                channel.basicNack(deliveryTag, false, false);
            } catch (IOException ioException) {
                log.error("basicNack failed", ioException);
            }
        }
    }
}
