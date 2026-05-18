package com.biteblog.notify;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.biteblog.notify", "com.biteblog.common"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.biteblog.notify.client")
@MapperScan("com.biteblog.notify.mapper")
@EnableScheduling
public class NotifyApplication {

    public static void main(String[] args) {
        // Keep compatibility with old Java-serialized Map messages that may still be in RabbitMQ.
        System.setProperty("spring.amqp.deserialization.trust.all", "true");
        SpringApplication.run(NotifyApplication.class, args);
    }
}
