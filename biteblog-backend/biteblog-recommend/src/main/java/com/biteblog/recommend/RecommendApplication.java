package com.biteblog.recommend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.biteblog.recommend", "com.biteblog.common"})
@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.biteblog.recommend.client")
@MapperScan("com.biteblog.recommend.mapper")
@EnableScheduling
public class RecommendApplication {
    public static void main(String[] args) {
        SpringApplication.run(RecommendApplication.class, args);
    }
}
