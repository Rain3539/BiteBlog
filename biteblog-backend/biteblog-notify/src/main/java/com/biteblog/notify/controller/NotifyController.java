package com.biteblog.notify.controller;

import com.biteblog.common.result.Result;
import com.biteblog.notify.service.NotifyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 通知服务：GET /notify/list、unread-count；POST read-all、/{id}/read
 */
@RestController
@RequestMapping("/notify")
@RequiredArgsConstructor
public class NotifyController {

    private final NotifyService notifyService;

    @GetMapping("/health")
    public Result<Map<String, String>> health() {
        return Result.success(Map.of("service", "notify-service", "status", "UP"));
    }

    /** 通知列表：传统分页 list + total（未读数请用 /notify/unread-count） */
    @GetMapping("/list")
    public Result<Map<String, Object>> list(@RequestHeader("X-User-Id") Long userId,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return Result.success(notifyService.pageList(userId, page, size));
    }

    @PostMapping("/read-all")
    public Result<Void> readAll(@RequestHeader("X-User-Id") Long userId) {
        notifyService.markAllRead(userId);
        return Result.success();
    }

    @PostMapping("/{id}/read")
    public Result<Void> readOne(@PathVariable Long id,
                                @RequestHeader("X-User-Id") Long userId) {
        notifyService.markRead(userId, id);
        return Result.success();
    }

    @GetMapping("/unread-count")
    public Result<Map<String, Long>> unreadCount(@RequestHeader("X-User-Id") Long userId) {
        long c = notifyService.countUnread(userId);
        return Result.success(Map.of("unreadCount", c));
    }
}
