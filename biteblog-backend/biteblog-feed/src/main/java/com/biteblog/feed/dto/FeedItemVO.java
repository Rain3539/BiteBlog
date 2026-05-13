package com.biteblog.feed.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class FeedItemVO {
    private Long postId;
    private Long authorId;
    private String authorName;
    private String title;
    private String coverUrl;
    private String shopName;
    private Integer likeCount;
    private Integer collectCount;
    private Integer commentCount;
    private LocalDateTime createdAt;
}
