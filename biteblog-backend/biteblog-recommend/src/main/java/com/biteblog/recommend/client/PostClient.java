package com.biteblog.recommend.client;

import com.biteblog.common.result.Result;
import com.biteblog.recommend.client.dto.PostDetailDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "post-service")
public interface PostClient {

    @GetMapping("/post/{id}")
    Result<PostDetailDTO> getPostDetail(@PathVariable("id") Long id);
}
