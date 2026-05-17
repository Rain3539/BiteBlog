package com.biteblog.notify.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 与 Rank 相同交换机名，独立队列，消费 interaction.*（与 Post 发布的路由键一致）
 */
@Configuration
public class NotifyRabbitConfig {

    public static final String INTERACTION_EXCHANGE = "biteblog.interaction";
    public static final String NOTIFY_INTERACTION_QUEUE = "notify.interaction.queue";

    /** 死信交换机：消费异常的消息转投此处，避免永久丢失 */
    public static final String NOTIFY_DLX = "notify.dlx";
    /** 死信队列：运维可手动检查或重放 */
    public static final String NOTIFY_DLQ = "notify.dead.queue";

    @Bean
    public TopicExchange notifyInteractionExchange() {
        return ExchangeBuilder.topicExchange(INTERACTION_EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange notifyDlx() {
        return ExchangeBuilder.directExchange(NOTIFY_DLX).durable(true).build();
    }

    @Bean
    public Queue notifyDeadQueue() {
        return QueueBuilder.durable(NOTIFY_DLQ).build();
    }

    @Bean
    public Binding notifyDeadBinding() {
        return BindingBuilder.bind(notifyDeadQueue()).to(notifyDlx()).with(NOTIFY_DLQ);
    }

    @Bean
    public Queue notifyInteractionQueue() {
        // 消费失败（nack + requeue=false）或超过最大重试次数后，消息路由到死信队列
        return QueueBuilder.durable(NOTIFY_INTERACTION_QUEUE)
                .withArgument("x-dead-letter-exchange", NOTIFY_DLX)
                .withArgument("x-dead-letter-routing-key", NOTIFY_DLQ)
                .build();
    }

    @Bean
    public Binding notifyInteractionBinding() {
        return BindingBuilder.bind(notifyInteractionQueue())
                .to(notifyInteractionExchange())
                .with("interaction.*");
    }
}
