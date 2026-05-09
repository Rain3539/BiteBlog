package com.biteblog.notify.controller;

import com.biteblog.common.result.Result;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 通知服务 Controller（骨架）
 * 负责人: 组员 5（Notify + WebSocket）
 */
@RestController
@RequestMapping("/notify")
public class NotifyController {

    /** 通知列表 GET /notify/list */
    @GetMapping("/list")
    public Result<?> list(@RequestHeader("X-User-Id") Long userId,
                          @RequestParam(defaultValue = "1") int page,
                          @RequestParam(defaultValue = "20") int size) {
        // TODO: 分页查询通知 → 包含发送者信息 + 关联笔记
        return Result.success(Map.of("list", java.util.List.of(), "total", 0, "unreadCount", 0));
    }

    /** 全部已读 POST /notify/read-all */
    @PostMapping("/read-all")
    public Result<?> readAll(@RequestHeader("X-User-Id") Long userId) {
        // TODO: 批量更新已读状态
        return Result.success();
    }

    /** 标记单条已读 POST /notify/{id}/read */
    @PostMapping("/{id}/read")
    public Result<?> readOne(@PathVariable Long id,
                             @RequestHeader("X-User-Id") Long userId) {
        // TODO: 更新单条通知已读状态
        return Result.success();
    }

    /** 未读数 GET /notify/unread-count */
    @GetMapping("/unread-count")
    public Result<?> unreadCount(@RequestHeader("X-User-Id") Long userId) {
        // TODO: 查询未读通知数
        return Result.success(Map.of("unreadCount", 0));
    }
}
