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

    @Bean
    public TopicExchange notifyInteractionExchange() {
        return ExchangeBuilder.topicExchange(INTERACTION_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue notifyInteractionQueue() {
        return QueueBuilder.durable(NOTIFY_INTERACTION_QUEUE).build();
    }

    @Bean
    public Binding notifyInteractionBinding() {
        return BindingBuilder.bind(notifyInteractionQueue())
                .to(notifyInteractionExchange())
                .with("interaction.*");
    }
}
