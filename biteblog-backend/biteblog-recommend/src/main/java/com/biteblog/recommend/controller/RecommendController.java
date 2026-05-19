package com.biteblog.recommend.controller;

import com.biteblog.common.result.Result;
import com.biteblog.recommend.dto.ExposureRequest;
import com.biteblog.recommend.dto.RecommendResponse;
import com.biteblog.recommend.service.RecommendPrecomputeService;
import com.biteblog.recommend.service.RecommendService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/recommend")
@RequiredArgsConstructor
public class RecommendController {

    private final RecommendService recommendService;
    private final RecommendPrecomputeService recommendPrecomputeService;

    @GetMapping("/discover")
    public Result<RecommendResponse> discover(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                              @RequestParam(required = false) Long cursor,
                                              @RequestParam(defaultValue = "20") int size,
                                              @RequestParam(required = false) String tag,
                                              @RequestParam(required = false) String city) {
        if (userId == null) {
            return Result.fail(400, "missing X-User-Id");
        }
        return Result.success(recommendService.discover(userId, cursor, size, tag, city));
    }

    @PostMapping("/exposures")
    public Result<Map<String, Object>> saveExposures(@RequestHeader(value = "X-User-Id", required = false) Long userId,
                                                     @RequestBody ExposureRequest request) {
        if (userId == null) {
            return Result.fail(400, "missing X-User-Id");
        }
        return Result.success(recommendService.saveExposures(userId, request == null ? null : request.getPostIds()));
    }

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.success(Map.of("service", "recommend-service", "status", "UP"));
    }

    @PostMapping("/internal/precompute")
    public Result<Map<String, Object>> precompute() {
        return Result.success(recommendPrecomputeService.precompute());
    }
}
