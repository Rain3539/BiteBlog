package com.biteblog.rank.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RankRabbitConfig {
    public static final String POST_EXCHANGE = "biteblog.post";
    public static final String INTERACTION_EXCHANGE = "biteblog.interaction";

    public static final String NOTE_PUBLISHED_QUEUE = "rank.note.published.queue";
    public static final String NOTE_DELETED_QUEUE = "rank.note.deleted.queue";
    public static final String INTERACTION_QUEUE = "rank.interaction.queue";

    @Bean
    public TopicExchange postExchange() {
        return ExchangeBuilder.topicExchange(POST_EXCHANGE).durable(true).build();
    }

    @Bean
    public TopicExchange interactionExchange() {
        return ExchangeBuilder.topicExchange(INTERACTION_EXCHANGE).durable(true).build();
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
    public Queue interactionQueue() {
        return QueueBuilder.durable(INTERACTION_QUEUE).build();
    }

    @Bean
    public Binding notePublishedBinding() {
        return BindingBuilder.bind(notePublishedQueue()).to(postExchange()).with("note.published");
    }

    @Bean
    public Binding noteDeletedBinding() {
        return BindingBuilder.bind(noteDeletedQueue()).to(postExchange()).with("note.deleted");
    }

    @Bean
    public Binding interactionBinding() {
        return BindingBuilder.bind(interactionQueue()).to(interactionExchange()).with("interaction.*");
    }
}
