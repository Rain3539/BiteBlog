package com.biteblog.recommend.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RecommendRabbitConfig {

    public static final String POST_EXCHANGE = "biteblog.post";
    public static final String INTERACTION_EXCHANGE = "biteblog.interaction";

    public static final String NOTE_PUBLISHED_QUEUE = "recommend.note.published.queue";
    public static final String NOTE_DELETED_QUEUE = "recommend.note.deleted.queue";
    public static final String INTERACTION_QUEUE = "recommend.interaction.queue";

    @Bean
    public TopicExchange recommendPostExchange() {
        return ExchangeBuilder.topicExchange(POST_EXCHANGE).durable(true).build();
    }

    @Bean
    public TopicExchange recommendInteractionExchange() {
        return ExchangeBuilder.topicExchange(INTERACTION_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue recommendNotePublishedQueue() {
        return QueueBuilder.durable(NOTE_PUBLISHED_QUEUE).build();
    }

    @Bean
    public Queue recommendNoteDeletedQueue() {
        return QueueBuilder.durable(NOTE_DELETED_QUEUE).build();
    }

    @Bean
    public Queue recommendInteractionQueue() {
        return QueueBuilder.durable(INTERACTION_QUEUE).build();
    }

    @Bean
    public Binding recommendNotePublishedBinding() {
        return BindingBuilder.bind(recommendNotePublishedQueue())
                .to(recommendPostExchange())
                .with("note.published");
    }

    @Bean
    public Binding recommendNoteDeletedBinding() {
        return BindingBuilder.bind(recommendNoteDeletedQueue())
                .to(recommendPostExchange())
                .with("note.deleted");
    }

    @Bean
    public Binding recommendInteractionBinding() {
        return BindingBuilder.bind(recommendInteractionQueue())
                .to(recommendInteractionExchange())
                .with("interaction.*");
    }
}
