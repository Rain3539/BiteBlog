package com.biteblog.rank.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RankItemVO {
    private Integer rankNo;
    private Long postId;
    private Long authorId;
    private String title;
    private String shopName;
    private Integer likeCount;
    private Integer collectCount;
    private Integer commentCount;
    private Double hotScore;
    private LocalDateTime createdAt;
}
