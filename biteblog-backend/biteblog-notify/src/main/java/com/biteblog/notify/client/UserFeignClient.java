package com.biteblog.notify.client;

import com.biteblog.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Map;

/**
 * 拉取发送者展示信息
 */
@FeignClient(name = "user-service", path = "/user")
public interface UserFeignClient {

    @GetMapping("/{id}")
    Result<Map<String, Object>> getUser(@PathVariable("id") Long id);
}
