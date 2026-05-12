package com.biteblog.notify;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = {"com.biteblog.notify", "com.biteblog.common"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.biteblog.notify.client")
@MapperScan("com.biteblog.notify.mapper")
public class NotifyApplication {

    public static void main(String[] args) {
        // Post 用 Map.of() 发 MQ，JDK 序列化后为 java.util.CollSer。
        // Spring AMQP 3.x 的白名单检查走 System.getProperty()，application.yml 无效，必须在此设置。
        System.setProperty("spring.amqp.deserialization.trust.all", "true");
        SpringApplication.run(NotifyApplication.class, args);
    }
}
