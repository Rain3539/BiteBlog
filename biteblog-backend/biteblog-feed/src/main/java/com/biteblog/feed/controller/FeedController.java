package com.biteblog.feed.controller;

import com.biteblog.common.result.Result;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Feed 服务 Controller（骨架）
 * 负责人: 组员 4（Feed + Recommend Service）
 */
@RestController
@RequestMapping("/feed")
public class FeedController {

    /** 首页 Feed 流 GET /feed/timeline */
    @GetMapping("/timeline")
    public Result<?> timeline(@RequestHeader("X-User-Id") Long userId,
                              @RequestParam(required = false) Long cursor,
                              @RequestParam(defaultValue = "20") int size) {
        // TODO: 读 Redis inbox:{userId} ZSet → 大V 实时拉取合并 → 过滤已删除 → 聚合摘要
        return Result.success(Map.of("list", java.util.List.of(), "cursor", null, "hasMore", false));
    }

    /** 附近探店 GET /feed/nearby */
    @GetMapping("/nearby")
    public Result<?> nearby(@RequestHeader("X-User-Id") Long userId,
                            @RequestParam Double longitude,
                            @RequestParam Double latitude,
                            @RequestParam(defaultValue = "3") int radius,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "20") int size) {
        // TODO: Redis GEO 范围查询 → 查 ES 获取摘要 → 按距离/热度排序
        return Result.success(Map.of("list", java.util.List.of(), "total", 0));
    }
}
