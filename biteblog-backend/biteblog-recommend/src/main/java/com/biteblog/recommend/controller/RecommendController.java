package com.biteblog.recommend.controller;

import com.biteblog.common.result.Result;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 推荐服务 Controller（骨架）
 * 负责人: 组员 4（Feed + Recommend Service）
 */
@RestController
@RequestMapping("/recommend")
public class RecommendController {

    /** 发现页推荐 GET /recommend/discover */
    @GetMapping("/discover")
    public Result<?> discover(@RequestHeader("X-User-Id") Long userId,
                              @RequestParam(required = false) Long cursor,
                              @RequestParam(defaultValue = "20") int size) {
        // TODO: 判断行为数据量 → 足够: ES 检索候选 → 标签推荐(60%) + ItemCF(40%)
        //       不够: 热度加权兜底 → Redis Set 去除已曝光 → 返回推荐列表
        return Result.success(Map.of("list", java.util.List.of(), "cursor", null, "hasMore", false));
    }
}
