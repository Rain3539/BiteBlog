package com.biteblog.post.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class PostDetailVO {

    private Long postId;
    private Long authorId;
    private String authorName;
    private String authorAvatar;
    private String title;
    private String content;
    private String shopName;
    private String address;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private List<String> imageUrls;
    private Integer scoreColor;
    private Integer scoreSmell;
    private Integer scoreTaste;
    private Integer likeCount;
    private Integer collectCount;
    private Integer commentCount;
    private Boolean liked;
    private Boolean favorited;
    private LocalDateTime createdAt;
}
