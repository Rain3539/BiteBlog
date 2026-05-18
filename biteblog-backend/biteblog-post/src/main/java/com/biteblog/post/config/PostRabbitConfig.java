package com.biteblog.post.config;

import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PostRabbitConfig {

    public static final String POST_EXCHANGE = "biteblog.post";
    public static final String INTERACTION_EXCHANGE = "biteblog.interaction";

    @Bean
    public TopicExchange postExchange() {
        return ExchangeBuilder.topicExchange(POST_EXCHANGE).durable(true).build();
    }

    @Bean
    public TopicExchange interactionExchange() {
        return ExchangeBuilder.topicExchange(INTERACTION_EXCHANGE).durable(true).build();
    }
}
