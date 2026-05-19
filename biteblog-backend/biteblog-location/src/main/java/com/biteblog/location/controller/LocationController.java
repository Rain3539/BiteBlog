package com.biteblog.location.controller;

import com.biteblog.common.result.Result;
import com.biteblog.location.dto.NearbyMarkerVO;
import com.biteblog.location.dto.PoiItemVO;
import com.biteblog.location.service.LocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 位置服务 Controller
 * 负责人: 成员 4（Location Service）
 */
@RestController
@RequestMapping("/location")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;

    /** 健康检查 GET /location/health */
    @GetMapping("/health")
    public Result<?> health() {
        return Result.success(Map.of("service", "location-service", "status", "UP"));
    }

    /** POI 搜索（高德 API 代理） GET /location/poi/search */
    @GetMapping("/poi/search")
    public Result<?> searchPoi(@RequestParam("keyword") String keyword,
                               @RequestParam(name = "city", required = false) String city) {
        List<PoiItemVO> list = locationService.searchPoi(keyword, city);
        return Result.success(Map.of("list", list));
    }

    /** 获取附近笔记坐标 GET /location/nearby/markers */
    @GetMapping("/nearby/markers")
    public Result<?> nearbyMarkers(@RequestParam("longitude") Double longitude,
                                   @RequestParam("latitude") Double latitude,
                                   @RequestParam(name = "radius", defaultValue = "3") int radius) {
        List<NearbyMarkerVO> markers = locationService.nearbyMarkers(longitude, latitude, radius);
        return Result.success(Map.of("markers", markers));
    }
}
