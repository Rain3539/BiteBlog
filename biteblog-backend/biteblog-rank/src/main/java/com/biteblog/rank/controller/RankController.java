package com.biteblog.rank.controller;

import com.biteblog.common.result.Result;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 排行服务 Controller（骨架）
 * 负责人: 组员 3（Post + Location + Rank Service）
 */
@RestController
@RequestMapping("/rank")
public class RankController {

    /** 热度榜 GET /rank/top10 */
    @GetMapping("/top10")
    public Result<?> top10(@RequestParam(defaultValue = "daily") String type) {
        // TODO: Redis ZSet 取 Top10 → 查 ES 获取笔记摘要
        return Result.success(Map.of("list", java.util.List.of()));
    }

    /** 热度榜（分页） GET /rank/list */
    @GetMapping("/list")
    public Result<?> list(@RequestParam(defaultValue = "daily") String type,
                          @RequestParam(defaultValue = "1") int page,
                          @RequestParam(defaultValue = "20") int size) {
        // TODO: 分页查询排行
        return Result.success(Map.of("list", java.util.List.of(), "total", 0));
    }
}
