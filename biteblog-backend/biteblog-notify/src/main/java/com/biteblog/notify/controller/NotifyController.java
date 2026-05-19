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

    /**
     * 通知列表（传统分页）。
     *
     * @param type       可选，like / collect / comment，不传则返回全部类型
     * @param readStatus 可选，0=未读 1=已读，不传则返回全部
     */
    @GetMapping("/list")
    public Result<Map<String, Object>> list(@RequestHeader("X-User-Id") Long userId,
                                            @RequestParam(name = "page", defaultValue = "1") int page,
                                            @RequestParam(name = "size", defaultValue = "20") int size,
                                            @RequestParam(name = "type", required = false) String type,
                                            @RequestParam(name = "readStatus", required = false) Integer readStatus) {
        return Result.success(notifyService.pageList(userId, page, size, type, readStatus));
    }

    @PostMapping("/read-all")
    public Result<Void> readAll(@RequestHeader("X-User-Id") Long userId) {
        notifyService.markAllRead(userId);
        return Result.success();
    }

    @PostMapping("/{id}/read")
    public Result<Void> readOne(@PathVariable("id") Long id,
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
