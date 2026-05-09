package com.biteblog.post.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class CommentVO {

    private Long commentId;
    private Long userId;
    private String username;
    private String avatar;
    private String content;
    private Long parentId;
    private List<CommentVO> replies;
    private LocalDateTime createdAt;
}
