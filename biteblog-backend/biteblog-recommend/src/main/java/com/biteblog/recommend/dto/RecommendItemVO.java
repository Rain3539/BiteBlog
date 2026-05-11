package com.biteblog.recommend.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class RecommendItemVO {

    private Long postId;
    private Long authorId;
    private String title;
    private String coverUrl;
    private String shopName;
    private List<String> tags;
    private Long likeCount;
    private Long collectCount;
    private Long commentCount;
    private Double score;
    private String reason;
    private LocalDateTime createdAt;
}
