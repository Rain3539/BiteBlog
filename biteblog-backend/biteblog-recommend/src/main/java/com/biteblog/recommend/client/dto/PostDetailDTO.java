package com.biteblog.recommend.client.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class PostDetailDTO {

    private Long postId;
    private Long authorId;
    private String authorName;
    private String authorAvatar;
    private String title;
    private String shopName;
    private List<String> imageUrls;
    private Integer likeCount;
    private Integer collectCount;
    private Integer commentCount;
    private LocalDateTime createdAt;
}
