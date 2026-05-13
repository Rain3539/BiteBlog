package com.biteblog.feed.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeedRabbitConfig {

    public static final String POST_EXCHANGE = "biteblog.post";
    public static final String NOTE_PUBLISHED_QUEUE = "feed.note.published.queue";
    public static final String NOTE_DELETED_QUEUE = "feed.note.deleted.queue";

    @Bean
    public TopicExchange postExchange() {
        return ExchangeBuilder.topicExchange(POST_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue notePublishedQueue() {
        return QueueBuilder.durable(NOTE_PUBLISHED_QUEUE).build();
    }

    @Bean
    public Queue noteDeletedQueue() {
        return QueueBuilder.durable(NOTE_DELETED_QUEUE).build();
    }

    @Bean
    public Binding notePublishedBinding() {
        return BindingBuilder.bind(notePublishedQueue()).to(postExchange()).with("note.published");
    }

    @Bean
    public Binding noteDeletedBinding() {
        return BindingBuilder.bind(noteDeletedQueue()).to(postExchange()).with("note.deleted");
    }
}
