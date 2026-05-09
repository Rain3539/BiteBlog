package com.biteblog.rank.controller;

import com.biteblog.common.result.Result;
import com.biteblog.rank.service.RankService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/rank")
@RequiredArgsConstructor
public class RankController {
    private final RankService rankService;

    /** 热榜 Top10：GET /rank/top10?type=daily|weekly|all */
    @GetMapping("/top10")
    public Result<Map<String, Object>> top10(@RequestParam(defaultValue = "daily") String type) {
        return Result.success(rankService.getTop10(type));
    }

    /** 分页热榜：GET /rank/list?type=daily&page=1&size=20 */
    @GetMapping("/list")
    public Result<Map<String, Object>> list(@RequestParam(defaultValue = "daily") String type,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return Result.success(rankService.getRankList(type, page, size));
    }

    /** 手动重建缓存，便于初始化和测试：POST /rank/rebuild?type=daily|weekly|all */
    @PostMapping("/rebuild")
    public Result<Map<String, Object>> rebuild(@RequestParam(defaultValue = "daily") String type) {
        rankService.rebuild(type);
        return Result.success(Map.of("rebuilt", true, "type", type));
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.success(Map.of("service", "rank-service", "status", "UP"));
    }
}
