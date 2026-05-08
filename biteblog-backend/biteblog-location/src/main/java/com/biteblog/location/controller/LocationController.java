package com.biteblog.location.controller;

import com.biteblog.common.result.Result;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 位置服务 Controller（骨架）
 * 负责人: 组员 3（Post + Location + Rank Service）
 */
@RestController
@RequestMapping("/location")
public class LocationController {

    /** POI 搜索（高德 API 代理） GET /location/poi/search */
    @GetMapping("/poi/search")
    public Result<?> searchPoi(@RequestParam String keyword,
                               @RequestParam(required = false) String city) {
        // TODO: 调用高德 Web API → 返回 POI 列表
        return Result.success(Map.of("list", java.util.List.of()));
    }

    /** 获取附近笔记坐标 GET /location/nearby/markers */
    @GetMapping("/nearby/markers")
    public Result<?> nearbyMarkers(@RequestParam Double longitude,
                                   @RequestParam Double latitude,
                                   @RequestParam(defaultValue = "3") int radius) {
        // TODO: ES geo_distance 查询 → 返回笔记坐标点列表
        return Result.success(Map.of("markers", java.util.List.of()));
    }
}
