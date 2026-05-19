package com.biteblog.feed.controller;

import com.biteblog.common.result.Result;
import com.biteblog.feed.service.FeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Feed 服务 Controller
 * 推拉结合 Feed 流
 */
@RestController
@RequestMapping("/feed")
@RequiredArgsConstructor
public class FeedController {

    private final FeedService feedService;

    /** 关注 Feed 流 GET /feed/timeline */
    @GetMapping("/timeline")
    public Result<Map<String, Object>> timeline(@RequestHeader("X-User-Id") Long userId,
                                                 @RequestParam(name = "cursor", required = false) Long cursor,
                                                 @RequestParam(name = "size", defaultValue = "20") int size) {
        return Result.success(feedService.getTimeline(userId, cursor, size));
    }
}
