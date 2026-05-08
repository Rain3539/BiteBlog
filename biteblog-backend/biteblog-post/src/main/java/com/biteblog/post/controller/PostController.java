package com.biteblog.post.controller;

import com.biteblog.common.result.Result;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 笔记服务 Controller（骨架）
 * 负责人: 组员 3（Post + Location + Rank Service）
 */
@RestController
@RequestMapping("/post")
public class PostController {

    /** 发布笔记 POST /post/publish */
    @PostMapping("/publish")
    public Result<?> publish(@RequestBody Map<String, Object> params,
                             @RequestHeader("X-User-Id") Long userId) {
        // TODO: 校验内容 → 图片上传 MinIO → 写 MySQL note → 同步 ES post_index → 发 MQ 事件
        return Result.success(Map.of("postId", 1, "createdAt", "2026-05-06T12:00:00"));
    }

    /** 笔记详情 GET /post/{id} */
    @GetMapping("/{id}")
    public Result<?> getDetail(@PathVariable Long id,
                               @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        // TODO: Redis 缓存 → 未命中查 ES → 返回详情 + 图片 + 互动状态
        return Result.success(Map.of("postId", id, "title", "stub", "content", "..."));
    }

    /** 点赞/取消点赞 POST /post/{id}/like */
    @PostMapping("/{id}/like")
    public Result<?> like(@PathVariable Long id,
                          @RequestHeader("X-User-Id") Long userId) {
        // TODO: Redis 幂等判断 → 写 MySQL note_like → 更新计数 → 发 MQ 通知
        return Result.success(Map.of("liked", true, "likeCount", 100));
    }

    /** 收藏/取消收藏 POST /post/{id}/favorite */
    @PostMapping("/{id}/favorite")
    public Result<?> favorite(@PathVariable Long id,
                              @RequestHeader("X-User-Id") Long userId) {
        // TODO: 同点赞逻辑
        return Result.success(Map.of("favorited", true, "collectCount", 50));
    }

    /** 发表评论 POST /post/{id}/comment */
    @PostMapping("/{id}/comment")
    public Result<?> comment(@PathVariable Long id,
                             @RequestBody Map<String, Object> params,
                             @RequestHeader("X-User-Id") Long userId) {
        // TODO: 写 MySQL comment → 同步 ES comment_index → 发 MQ 通知
        return Result.success(Map.of("commentId", 1));
    }

    /** 获取评论列表 GET /post/{id}/comments */
    @GetMapping("/{id}/comments")
    public Result<?> getComments(@PathVariable Long id,
                                 @RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        // TODO: ES 分页查询评论
        return Result.success(Map.of("list", java.util.List.of(), "total", 0));
    }

    /** 删除笔记 DELETE /post/{id} */
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id,
                            @RequestHeader("X-User-Id") Long userId) {
        // TODO: 校验权限 → 逻辑删除 → 发 MQ 异步清理 Feed
        return Result.success();
    }
}
