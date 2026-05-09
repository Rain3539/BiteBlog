package com.biteblog.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CommentRequest {

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 500, message = "评论最长500字")
    private String content;

    /** null=顶级评论, 非null=回复某条评论 */
    private Long parentId;
}
