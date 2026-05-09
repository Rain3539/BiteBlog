package com.biteblog.post.controller;

import com.biteblog.common.result.Result;
import com.biteblog.post.dto.CommentRequest;
import com.biteblog.post.dto.PostDetailVO;
import com.biteblog.post.dto.PublishNoteRequest;
import com.biteblog.post.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/post")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;
    private final LikeService likeService;
    private final FavoriteService favoriteService;
    private final CommentService commentService;
    private final ImageService imageService;

    /** 上传图片 POST /post/upload-image */
    @PostMapping("/upload-image")
    public Result<String> uploadImage(@RequestParam("file") MultipartFile file) {
        String url = imageService.uploadImage(file);
        return Result.success(url);
    }

    /** 发布笔记 POST /post/publish */
    @PostMapping("/publish")
    public Result<Map<String, Object>> publish(@Valid @RequestBody PublishNoteRequest req,
                                                @RequestHeader("X-User-Id") Long userId) {
        Long postId = postService.publishNote(req, userId);
        return Result.success(Map.of("postId", postId));
    }

    /** 笔记详情 GET /post/{id} */
    @GetMapping("/{id}")
    public Result<PostDetailVO> getDetail(@PathVariable Long id,
                                          @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        return Result.success(postService.getDetail(id, userId));
    }

    /** 点赞/取消点赞 POST /post/{id}/like */
    @PostMapping("/{id}/like")
    public Result<Map<String, Object>> like(@PathVariable Long id,
                                            @RequestHeader("X-User-Id") Long userId) {
        boolean liked = likeService.toggleLike(id, userId);
        return Result.success(Map.of("liked", liked));
    }

    /** 收藏/取消收藏 POST /post/{id}/favorite */
    @PostMapping("/{id}/favorite")
    public Result<Map<String, Object>> favorite(@PathVariable Long id,
                                                @RequestHeader("X-User-Id") Long userId) {
        boolean favorited = favoriteService.toggleFavorite(id, userId);
        return Result.success(Map.of("favorited", favorited));
    }

    /** 发表评论 POST /post/{id}/comment */
    @PostMapping("/{id}/comment")
    public Result<Map<String, Object>> comment(@PathVariable Long id,
                                               @Valid @RequestBody CommentRequest req,
                                               @RequestHeader("X-User-Id") Long userId) {
        Long commentId = commentService.publishComment(id, userId, req.getContent(), req.getParentId());
        return Result.success(Map.of("commentId", commentId));
    }

    /** 获取评论列表 GET /post/{id}/comments */
    @GetMapping("/{id}/comments")
    public Result<Map<String, Object>> getComments(@PathVariable Long id,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return Result.success(commentService.getComments(id, page, size));
    }

    /** 删除笔记 DELETE /post/{id} */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               @RequestHeader("X-User-Id") Long userId) {
        postService.deleteNote(id, userId);
        return Result.success();
    }

    /** ES 全文搜索 GET /post/search */
    @GetMapping("/search")
    public Result<Map<String, Object>> search(@RequestParam String keyword,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return Result.success(postService.search(keyword, page, size));
    }
}
