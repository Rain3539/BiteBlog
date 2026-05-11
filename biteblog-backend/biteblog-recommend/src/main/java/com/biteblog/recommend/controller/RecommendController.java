package com.biteblog.recommend.controller;

import com.biteblog.common.result.Result;
import com.biteblog.recommend.dto.RecommendResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/recommend")
public class RecommendController {

    @GetMapping("/discover")
    public Result<RecommendResponse> discover(@RequestHeader("X-User-Id") Long userId,
                                              @RequestParam(required = false) Long cursor,
                                              @RequestParam(defaultValue = "20") int size) {
        return Result.success(RecommendResponse.empty());
    }
}
