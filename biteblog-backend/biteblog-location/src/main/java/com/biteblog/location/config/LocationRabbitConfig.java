package com.biteblog.location.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LocationRabbitConfig {
    public static final String POST_EXCHANGE = "biteblog.post";
    public static final String NOTE_PUBLISHED_QUEUE = "location.note.published.queue";

    @Bean
    public TopicExchange postExchange() {
        return ExchangeBuilder.topicExchange(POST_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue notePublishedQueue() {
        return QueueBuilder.durable(NOTE_PUBLISHED_QUEUE).build();
    }

    @Bean
    public Binding notePublishedBinding() {
        return BindingBuilder.bind(notePublishedQueue()).to(postExchange()).with("note.published");
    }
}
