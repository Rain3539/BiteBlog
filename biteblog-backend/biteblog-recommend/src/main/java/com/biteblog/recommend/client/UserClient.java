package com.biteblog.recommend.client;

import com.biteblog.common.result.Result;
import com.biteblog.recommend.client.dto.UserProfileDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "user-service")
public interface UserClient {

    @GetMapping("/user/{id}")
    Result<UserProfileDTO> getUserProfile(@PathVariable("id") Long id);
}
