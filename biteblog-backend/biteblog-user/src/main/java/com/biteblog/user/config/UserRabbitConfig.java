package com.biteblog.user.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UserRabbitConfig {

    public static final String USER_LIKE_QUEUE = "biteblog.user.like";

    @Bean
    public Queue userLikeQueue() {
        return QueueBuilder.durable(USER_LIKE_QUEUE).build();
    }

    @Bean
    public Binding userLikeBinding() {
        return BindingBuilder.bind(userLikeQueue())
                .to(new TopicExchange("biteblog.interaction"))
                .with("interaction.like");
    }
}
